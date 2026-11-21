// Runs Smart Turn (an ONNX model, see Turn.kt) on the onnxruntime that sherpa-onnx already ships, found at run time,
// so Droid carries one copy of it. The headers in onnxruntime/ are onnxruntime 1.28's (MIT); sherpa-onnx's library is
// 1.28.x, and its API takes any version up to its own.
#include <dlfcn.h>
#include <jni.h>
#include <stdlib.h>
#include "onnxruntime/onnxruntime_c_api.h"

static const OrtApi *ort;
static OrtEnv *env;

typedef struct {
    OrtSession *session;
    OrtMemoryInfo *memory;
} Model;

// Throws [status]'s message as an IOException and returns 0, or returns 1 if it is fine.
static int ok(JNIEnv *e, OrtStatus *status) {
    if (status == NULL) return 1;
    (*e)->ThrowNew(e, (*e)->FindClass(e, "java/io/IOException"), ort->GetErrorMessage(status));
    ort->ReleaseStatus(status);
    return 0;
}

JNIEXPORT jlong JNICALL Java_com_adetkov_droid_Turn_open(JNIEnv *e, jclass cls, jstring path, jint threads) {
    if (ort == NULL) {
        void *lib = dlopen("libonnxruntime.so", RTLD_NOW);
        const OrtApiBase *(*base)(void) = lib ? (const OrtApiBase *(*)(void)) dlsym(lib, "OrtGetApiBase") : NULL;
        ort = base ? base()->GetApi(ORT_API_VERSION) : NULL;
        if (ort == NULL) {
            (*e)->ThrowNew(e, (*e)->FindClass(e, "java/io/IOException"), "onnxruntime not found");
            return 0;
        }
    }
    if (env == NULL && !ok(e, ort->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "turn", &env))) return 0;
    OrtSessionOptions *options;
    if (!ok(e, ort->CreateSessionOptions(&options))) return 0;
    int opened = ok(e, ort->SetIntraOpNumThreads(options, threads)) && ok(e, ort->SetInterOpNumThreads(options, 1))
        && ok(e, ort->SetSessionExecutionMode(options, ORT_SEQUENTIAL))
        && ok(e, ort->SetSessionGraphOptimizationLevel(options, ORT_ENABLE_ALL));
    OrtSession *session = NULL;
    if (opened) {
        const char *file = (*e)->GetStringUTFChars(e, path, NULL);
        opened = ok(e, ort->CreateSession(env, file, options, &session));
        (*e)->ReleaseStringUTFChars(e, path, file);
    }
    ort->ReleaseSessionOptions(options);
    if (!opened) return 0;
    Model *m = calloc(1, sizeof(Model));
    m->session = session;
    if (!ok(e, ort->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &m->memory))) {
        ort->ReleaseSession(session);
        free(m);
        return 0;
    }
    return (jlong) (intptr_t) m;
}

// The model's output for [features] (80 × frames log-mel, row by row): the probability that the turn is complete.
JNIEXPORT jfloat JNICALL Java_com_adetkov_droid_Turn_run(JNIEnv *e, jclass cls, jlong handle, jfloatArray features) {
    Model *m = (Model *) (intptr_t) handle;
    jsize n = (*e)->GetArrayLength(e, features);
    jfloat *x = (*e)->GetFloatArrayElements(e, features, NULL);
    int64_t shape[3] = {1, 80, n / 80};
    OrtValue *in = NULL, *out = NULL;
    float p = -1;
    const char *inName = "input_features", *outName = "logits";
    if (ok(e, ort->CreateTensorWithDataAsOrtValue(m->memory, x, (size_t) n * sizeof(float), shape, 3,
                                                  ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &in))
        && ok(e, ort->Run(m->session, NULL, &inName, (const OrtValue *const *) &in, 1, &outName, 1, &out))) {
        float *y;
        if (ok(e, ort->GetTensorMutableData(out, (void **) &y))) p = y[0];
    }
    if (out) ort->ReleaseValue(out);
    if (in) ort->ReleaseValue(in);
    (*e)->ReleaseFloatArrayElements(e, features, x, JNI_ABORT);
    return p;
}

JNIEXPORT void JNICALL Java_com_adetkov_droid_Turn_close(JNIEnv *e, jclass cls, jlong handle) {
    Model *m = (Model *) (intptr_t) handle;
    ort->ReleaseMemoryInfo(m->memory);
    ort->ReleaseSession(m->session);
    free(m);
}
