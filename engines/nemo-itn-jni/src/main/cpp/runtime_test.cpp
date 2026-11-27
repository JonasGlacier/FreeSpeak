// Exercises the production rewrite/load implementation on an ARM64 Android device.
#define FREESPEAK_ITN_RUNTIME_TEST
#include "nemo_itn_jni.cpp"
#include <iostream>
#include <cstdio>

static Graph mapping(const std::string &input, const std::string &output) {
    Graph graph;
    graph.AddState(); graph.SetStart(0);
    // Epsilon outputs/inputs deliberately exercise the actual composition path.
    for (unsigned char c : input) {
        auto next = graph.AddState();
        graph.AddArc(next - 1, Arc(c, 0, Arc::Weight::One(), next));
    }
    for (unsigned char c : output) {
        auto next = graph.AddState();
        graph.AddArc(next - 1, Arc(0, c, Arc::Weight::One(), next));
    }
    graph.SetFinal(graph.NumStates() - 1, Arc::Weight::One());
    fst::ArcSort(&graph, fst::ILabelCompare<Arc>());
    return graph;
}

int main(int argc, char **argv) {
    if (argc != 2) return 2;
    int failures = 0;
    auto check = [&](bool value, const char *name) {
        std::cout << (value ? "PASS " : "FAIL ") << name << '\n';
        if (!value) ++failures;
    };
    const std::string tagged = "tokens { cardinal { integer: \"123\" } }";
    auto classifier = mapping("one hundred twenty three", tagged);
    auto verbalizer = mapping(tagged, "123");
    std::string result;
    check(rewrite("one hundred twenty three", classifier, &result) && result == tagged, "classification");
    std::string written;
    check(rewrite(result, verbalizer, &written) && written == "123", "verbalization");
    result.clear();
    check(!rewrite("unmatched words", classifier, &result), "unmatched path");
    result.clear();
    const std::string unicode = u8"中文 日本語 café 😀 𠀀 [literal]";
    auto identity = mapping(unicode, unicode);
    check(rewrite(unicode, identity, &result) && result == unicode, "byte-level UTF-8");
    result.clear();
    check(!rewrite(std::string(32769, 'a'), identity, &result), "input limit");
    const std::string path = std::string(argv[1]) + "/itn-test.far";
    {
        std::unique_ptr<fst::FarWriter<Arc>> writer(fst::FarWriter<Arc>::Create(path));
        if (!writer) return 3;
        writer->Add("grammar", classifier);
    }
    auto loaded = load(path);
    result.clear();
    check(loaded && rewrite("one hundred twenty three", *loaded, &result) && result == tagged, "FAR load and rewrite");
    loaded.reset();
    std::remove(path.c_str());
    std::cout << "Failures: " << failures << '\n';
    return failures ? 1 : 0;
}
