#ifndef FREESPEAK_ITN_RUNTIME_TEST
#include <jni.h>
#endif
#include <fst/fstlib.h>
#include <fst/extensions/far/far.h>
#include <chrono>
#include <memory>
#include <string>

namespace {
using Arc = fst::StdArc;
using Graph = fst::VectorFst<Arc>;
struct Normalizer { std::unique_ptr<Graph> classify, verbalize; };

// The exporter writes exactly one standard, byte-labelled vector FST per archive.
std::unique_ptr<Graph> load(const std::string &path) {
    std::unique_ptr<fst::FarReader<Arc>> reader(fst::FarReader<Arc>::Open(path));
    if (!reader || !reader->Find("grammar") || reader->Done() || reader->GetKey() != "grammar") return nullptr;
    auto graph = std::make_unique<Graph>(*reader->GetFst());
    if (graph->Start() == fst::kNoStateId || graph->Properties(fst::kError, true)) return nullptr;
    fst::ArcSort(graph.get(), fst::ILabelCompare<Arc>());
    return graph;
}

#ifndef FREESPEAK_ITN_RUNTIME_TEST
std::string bytes(JNIEnv *env, jbyteArray value) {
    std::string result(env->GetArrayLength(value), '\0');
    env->GetByteArrayRegion(value, 0, result.size(), reinterpret_cast<jbyte *>(result.data()));
    return result;
}
#endif

bool rewrite(const std::string &text, const Graph &grammar, std::string *output) {
    if (text.empty() || text.size() > 32768 || text.find('\0') != std::string::npos) return false;
    Graph input;
    input.AddState(); input.SetStart(0);
    for (unsigned char c : text) {
        auto next = input.AddState();
        input.AddArc(next - 1, Arc(c, c, Arc::Weight::One(), next));
    }
    input.SetFinal(text.size(), Arc::Weight::One());
    fst::ComposeFst<Arc> lazy(input, grammar);
    Graph lattice;
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(2);
    size_t arcs = 0;
    // Materialize with limits rather than allowing an unbounded composition allocation.
    for (fst::StateIterator<fst::ComposeFst<Arc>> states(lazy); !states.Done(); states.Next()) {
        auto state = states.Value();
        if (state >= 100000 || std::chrono::steady_clock::now() > deadline) return false;
        while (lattice.NumStates() <= state) lattice.AddState();
        lattice.SetFinal(state, lazy.Final(state));
        for (fst::ArcIterator<fst::ComposeFst<Arc>> a(lazy, state); !a.Done(); a.Next()) {
            if (++arcs > 500000 || a.Value().nextstate >= 100000) return false;
            while (lattice.NumStates() <= a.Value().nextstate) lattice.AddState();
            lattice.AddArc(state, a.Value());
        }
    }
    lattice.SetStart(lazy.Start());
    if (lattice.Start() == fst::kNoStateId || lattice.Properties(fst::kError, true)) return false;
    Graph shortest;
    fst::ShortestPath(lattice, &shortest);
    auto state = shortest.Start();
    if (state == fst::kNoStateId || shortest.Properties(fst::kError, true)) return false;
    for (int steps = 0; steps < 32768; ++steps) {
        fst::ArcIterator<Graph> a(shortest, state);
        if (a.Done()) return shortest.Final(state) != Arc::Weight::Zero();
        auto arc = a.Value(); a.Next();
        if (!a.Done() || arc.olabel < 0 || arc.olabel > 255) return false;
        if (arc.olabel) output->push_back(static_cast<char>(arc.olabel));
        state = arc.nextstate;
    }
    return false;
}
}

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
