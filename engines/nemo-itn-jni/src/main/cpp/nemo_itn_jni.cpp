#ifndef FREESPEAK_ITN_RUNTIME_TEST
#include <jni.h>
#endif
#include <fst/fstlib.h>
#include <fst/extensions/far/far.h>
#include <chrono>
#include <cstdint>
#include <memory>
#include <queue>
#include <string>
#include <unordered_map>
#include <vector>

namespace {
using Arc = fst::StdArc;
using Graph = fst::VectorFst<Arc>;
using StateId = Arc::StateId;

// 256-bit reachability mask per grammar state:
// - bit 0: can reach a grammar Final state via 0 or more ilabel == 0 transitions
// - bit b (1..255): can reach an outgoing arc with ilabel == b via 0 or more ilabel == 0 transitions
// Because input strings are epsilon-free linear byte chains, any grammar transition whose target
// lacks the bit for the next input byte (or bit 0 at EOF) has zero paths to composition finality.
struct Mask256 {
    uint64_t w[4] = {0, 0, 0, 0};

    void set(unsigned bit) { w[bit >> 6] |= (uint64_t{1} << (bit & 63)); }
    bool test(unsigned bit) const { return (w[bit >> 6] & (uint64_t{1} << (bit & 63))) != 0; }

    bool merge_from(const Mask256 &other) {
        uint64_t changed = 0;
        for (int i = 0; i < 4; ++i) {
            uint64_t next = w[i] | other.w[i];
            changed |= (next ^ w[i]);
            w[i] = next;
        }
        return changed != 0;
    }
};

struct PreparedGrammar {
    std::unique_ptr<Graph> graph;
    std::vector<Mask256> lookahead;
};

struct Normalizer {
    std::unique_ptr<PreparedGrammar> classify, verbalize;
};

std::unique_ptr<PreparedGrammar> prepare(std::unique_ptr<Graph> graph) {
    if (!graph || graph->Start() == fst::kNoStateId || graph->Properties(fst::kError, true)) {
        return nullptr;
    }
    fst::ArcSort(graph.get(), fst::ILabelCompare<Arc>());
    const StateId num_states = graph->NumStates();
    auto out = std::make_unique<PreparedGrammar>();
    out->lookahead.resize(num_states);

    std::vector<std::vector<StateId>> rev_eps(num_states);
    std::queue<StateId> q;
    std::vector<uint8_t> in_queue(num_states, 1);

    for (StateId s = 0; s < num_states; ++s) {
        if (graph->Final(s) != Arc::Weight::Zero()) {
            out->lookahead[s].set(0);
        }
        for (fst::ArcIterator<Graph> a(*graph, s); !a.Done(); a.Next()) {
            const auto &arc = a.Value();
            if (arc.nextstate < 0 || arc.nextstate >= num_states) continue;
            if (arc.ilabel == 0) {
                rev_eps[arc.nextstate].push_back(s);
            } else if (arc.ilabel >= 1 && arc.ilabel <= 255) {
                out->lookahead[s].set(static_cast<unsigned>(arc.ilabel));
            }
        }
        q.push(s);
    }

    while (!q.empty()) {
        const StateId v = q.front();
        q.pop();
        in_queue[v] = 0;
        for (StateId u : rev_eps[v]) {
            if (out->lookahead[u].merge_from(out->lookahead[v]) && !in_queue[u]) {
                in_queue[u] = 1;
                q.push(u);
            }
        }
    }

    out->graph = std::move(graph);
    return out;
}

// The exporter writes one standard, byte-labelled vector FST ("grammar") per archive.
std::unique_ptr<PreparedGrammar> load(const std::string &path) {
    std::unique_ptr<fst::FarReader<Arc>> reader(fst::FarReader<Arc>::Open(path));
    if (!reader || !reader->Find("grammar") || reader->Done() || reader->GetKey() != "grammar") {
        return nullptr;
    }
    return prepare(std::make_unique<Graph>(*reader->GetFst()));
}

#ifndef FREESPEAK_ITN_RUNTIME_TEST
std::string bytes(JNIEnv *env, jbyteArray value) {
    std::string result(env->GetArrayLength(value), '\0');
    env->GetByteArrayRegion(value, 0, result.size(), reinterpret_cast<jbyte *>(result.data()));
    return result;
}
#endif

bool rewrite(const std::string &text, const PreparedGrammar &prepared, std::string *output) {
    if (text.empty() || text.size() > 32768 || text.find('\0') != std::string::npos) return false;
    const Graph &grammar = *prepared.graph;
    const StateId g_start = grammar.Start();
    if (g_start < 0 || g_start >= static_cast<StateId>(prepared.lookahead.size())) return false;

    const unsigned first_need = static_cast<unsigned char>(text[0]);
    if (!prepared.lookahead[g_start].test(first_need)) return false;

    Graph lattice;
    const StateId l_start = lattice.AddState();
    lattice.SetStart(l_start);

    std::unordered_map<StateId, StateId> cur_map;
    std::unordered_map<StateId, StateId> next_map;
    std::vector<std::pair<StateId, StateId>> queue;
    cur_map.reserve(256);
    next_map.reserve(256);
    queue.reserve(256);

    cur_map.emplace(g_start, l_start);
    queue.emplace_back(g_start, l_start);

    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(2);
    constexpr StateId kMaxStates = 250000;
    constexpr size_t kMaxArcs = 1000000;
    size_t total_arcs = 0;
    const size_t n = text.size();

    for (size_t pos = 0; pos <= n; ++pos) {
        if ((pos & 15u) == 0u && std::chrono::steady_clock::now() > deadline) return false;
        const unsigned need = (pos < n) ? static_cast<unsigned char>(text[pos]) : 0u;

        // 1. Expand ilabel == 0 closure within input position `pos`, pruning dead branches.
        for (size_t qi = 0; qi < queue.size(); ++qi) {
            const StateId gs = queue[qi].first;
            const StateId ls = queue[qi].second;
            for (fst::ArcIterator<Graph> a(grammar, gs); !a.Done(); a.Next()) {
                const auto &arc = a.Value();
                if (arc.ilabel != 0) break; // Arcs are sorted by ilabel; all 0-arcs come first.
                if (arc.nextstate < 0 || !prepared.lookahead[arc.nextstate].test(need)) continue;
                StateId target_ls;
                auto it = cur_map.find(arc.nextstate);
                if (it == cur_map.end()) {
                    if (lattice.NumStates() >= kMaxStates) return false;
                    target_ls = lattice.AddState();
                    cur_map.emplace(arc.nextstate, target_ls);
                    queue.emplace_back(arc.nextstate, target_ls);
                } else {
                    target_ls = it->second;
                }
                if (++total_arcs > kMaxArcs) return false;
                lattice.AddArc(ls, Arc(0, arc.olabel, arc.weight, target_ls));
            }
        }

        if (pos == n) {
            bool any_final = false;
            for (const auto &kv : cur_map) {
                const auto w = grammar.Final(kv.first);
                if (w != Arc::Weight::Zero()) {
                    lattice.SetFinal(kv.second, w);
                    any_final = true;
                }
            }
            if (!any_final) return false;
            break;
        }

        // 2. Consume byte `need` to advance to `pos + 1`, binary-searching ilabel-sorted arcs.
        const unsigned next_need = (pos + 1 < n) ? static_cast<unsigned char>(text[pos + 1]) : 0u;
        next_map.clear();
        std::vector<std::pair<StateId, StateId>> next_queue;
        next_queue.reserve(queue.size());

        for (const auto &entry : queue) {
            const StateId gs = entry.first;
            const StateId ls = entry.second;
            const size_t narcs = grammar.NumArcs(gs);
            if (narcs == 0) continue;

            fst::ArcIterator<Graph> aiter(grammar, gs);
            size_t lo = 0, hi = narcs;
            while (lo < hi) {
                const size_t mid = lo + (hi - lo) / 2;
                aiter.Seek(mid);
                if (aiter.Value().ilabel < static_cast<int>(need)) lo = mid + 1;
                else hi = mid;
            }
            for (aiter.Seek(lo); !aiter.Done(); aiter.Next()) {
                const auto &arc = aiter.Value();
                if (arc.ilabel != static_cast<int>(need)) break;
                if (arc.nextstate < 0 || !prepared.lookahead[arc.nextstate].test(next_need)) continue;
                StateId target_ls;
                auto it = next_map.find(arc.nextstate);
                if (it == next_map.end()) {
                    if (lattice.NumStates() >= kMaxStates) return false;
                    target_ls = lattice.AddState();
                    next_map.emplace(arc.nextstate, target_ls);
                    next_queue.emplace_back(arc.nextstate, target_ls);
                } else {
                    target_ls = it->second;
                }
                if (++total_arcs > kMaxArcs) return false;
                lattice.AddArc(ls, Arc(static_cast<int>(need), arc.olabel, arc.weight, target_ls));
            }
        }

        if (next_queue.empty()) return false;
        cur_map.swap(next_map);
        queue.swap(next_queue);
    }

    if (lattice.Properties(fst::kError, true)) return false;
    Graph shortest;
    fst::ShortestPath(lattice, &shortest);
    auto state = shortest.Start();
    if (state == fst::kNoStateId || shortest.Properties(fst::kError, true)) return false;
    for (int steps = 0; steps < 262144; ++steps) {
        fst::ArcIterator<Graph> a(shortest, state);
        if (a.Done()) return shortest.Final(state) != Arc::Weight::Zero();
        auto arc = a.Value();
        a.Next();
        if (!a.Done() || arc.olabel < 0 || arc.olabel > 255) return false;
        if (arc.olabel) output->push_back(static_cast<char>(arc.olabel));
        state = arc.nextstate;
    }
    return false;
}
} // namespace

#ifndef FREESPEAK_ITN_RUNTIME_TEST
extern "C" JNIEXPORT jlong JNICALL
Java_com_freespeak_engine_itn_NemoItnNative_create(JNIEnv *env, jobject, jbyteArray classify, jbyteArray verbalize) {
    try {
        auto value = std::make_unique<Normalizer>();
        value->classify = load(bytes(env, classify));
        value->verbalize = load(bytes(env, verbalize));
        if (!value->classify || !value->verbalize) return 0;
        return reinterpret_cast<jlong>(value.release());
    } catch (...) { return 0; }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_freespeak_engine_itn_NemoItnNative_rewrite(JNIEnv *env, jobject, jlong handle, jboolean classify, jbyteArray text) {
    try {
        auto *value = reinterpret_cast<Normalizer *>(handle);
        if (!value) return nullptr;
        std::string output;
        if (!rewrite(bytes(env, text), classify ? *value->classify : *value->verbalize, &output)) return nullptr;
        auto result = env->NewByteArray(output.size());
        if (result) env->SetByteArrayRegion(result, 0, output.size(), reinterpret_cast<const jbyte *>(output.data()));
        return result;
    } catch (...) { return nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_com_freespeak_engine_itn_NemoItnNative_release(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<Normalizer *>(handle);
}
#endif
