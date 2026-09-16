// Port of utils/record_infer/main.cpp, minus the camera, file I/O and arg parsing.
// The pipeline is kept numerically identical to the desktop tool: stretch-resize to
// 640x640 (no letterbox), /255 normalization, in0 -> out0, and a single-best-anchor
// postprocess with no NMS.

#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <jni.h>

#include <cmath>
#include <optional>
#include <string>

#include <net.h>
#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>

#define LOG_TAG "RATCv"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr float kConfThreshold = 0.3f;

struct Detection {
  float x, y, w, h, conf;
};

struct Detector {
  ncnn::Net net;
  int input_size = 640;
};

// Verbatim from record_infer: YOLO26 output is (4 + num_classes) rows by
// num_anchors columns, boxes already in input-resolution pixels and scores
// already sigmoid'd inside the graph. One class ("ball"), so the class index is
// discarded and only the max score matters.
std::optional<Detection> postprocess(int frame_w, int frame_h, int imgsz,
                                     float threshold, const ncnn::Mat &out) {
  float best_conf = threshold;
  std::optional<Detection> best;
  for (int i = 0; i < out.w; ++i) {
    // A backend that blows up numerically (fp16 overflow on some mobile GPUs) emits
    // inf/NaN here. Left unchecked those become a box drawn far off-frame, which
    // looks exactly like "the model detects nothing" rather than like a failure.
    bool box_ok = true;
    for (int c = 0; c < 4; ++c)
      box_ok = box_ok && std::isfinite(out.row(c)[i]);
    if (!box_ok)
      continue;

    float conf = 0;
    for (int c = 4; c < out.h; ++c)
      conf = std::max(conf, out.row(c)[i]);
    if (!std::isfinite(conf))
      continue;
    if (conf > best_conf) {
      best_conf = conf;
      best = Detection{out.row(0)[i] * frame_w / static_cast<float>(imgsz),
                       out.row(1)[i] * frame_h / static_cast<float>(imgsz),
                       out.row(2)[i] * frame_w / static_cast<float>(imgsz),
                       out.row(3)[i] * frame_h / static_cast<float>(imgsz),
                       conf};
    }
  }
  return best;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_sh_emilien_RATapp_cv_Detector_nativeInit(
    JNIEnv *env, jobject, jobject asset_manager, jstring model_dir,
    jint input_size) {
  AAssetManager *mgr = AAssetManager_fromJava(env, asset_manager);
  if (mgr == nullptr) {
    LOGE("AAssetManager_fromJava failed");
    return 0;
  }

  const char *dir = env->GetStringUTFChars(model_dir, nullptr);
  const std::string param = std::string(dir) + "/model.ncnn.param";
  const std::string bin = std::string(dir) + "/model.ncnn.bin";
  env->ReleaseStringUTFChars(model_dir, dir);

  auto *detector = new Detector();
  detector->input_size = input_size;

  if (detector->net.load_param(mgr, param.c_str()) ||
      detector->net.load_model(mgr, bin.c_str())) {
    LOGE("failed to load model: %s", param.c_str());
    delete detector;
    return 0;
  }
  LOGI("model loaded: %s @%d (cpu)", param.c_str(), detector->input_size);
  return reinterpret_cast<jlong>(detector);
}

JNIEXPORT void JNICALL Java_sh_emilien_RATapp_cv_Detector_nativeRelease(
    JNIEnv *, jobject, jlong handle) {
  delete reinterpret_cast<Detector *>(handle);
}

// Runs inference on a BGR CV_8UC3 Mat and draws record_infer's overlay onto it in
// place. Returns {conf, x, y, w, h} (centre-based, frame pixels), or an empty
// array when nothing cleared the threshold.
JNIEXPORT jfloatArray JNICALL Java_sh_emilien_RATapp_cv_Detector_nativeProcess(
    JNIEnv *env, jobject, jlong handle, jlong mat_addr) {
  auto *detector = reinterpret_cast<Detector *>(handle);
  auto *frame = reinterpret_cast<cv::Mat *>(mat_addr);
  if (detector == nullptr || frame == nullptr || frame->empty())
    return env->NewFloatArray(0);

  const int imgsz = detector->input_size;
  ncnn::Mat in = ncnn::Mat::from_pixels_resize(
      frame->data, ncnn::Mat::PIXEL_BGR2RGB, frame->cols, frame->rows,
      static_cast<int>(frame->step), imgsz, imgsz);
  const float norm[3] = {1 / 255.f, 1 / 255.f, 1 / 255.f};
  in.substract_mean_normalize(nullptr, norm);

  ncnn::Extractor ex = detector->net.create_extractor();
  ex.input("in0", in);
  ncnn::Mat out;
  ex.extract("out0", out);

  // Once per process: the output geometry pins down whether the graph matches what
  // postprocess() assumes — (4 + num_classes) rows by num_anchors columns. A
  // transposed or otherwise unexpected layout makes row(0..3) read across the wrong
  // axis, which shows up as boxes scaled far outside the frame rather than as an
  // obvious failure.
  static bool logged_shape = false;
  if (!logged_shape) {
    logged_shape = true;
    LOGI("out0 shape: w=%d h=%d c=%d dims=%d (expect h=4+num_classes, w=num_anchors)",
         out.w, out.h, out.c, out.dims);
  }

  auto det = postprocess(frame->cols, frame->rows, imgsz, kConfThreshold, out);

  if (det && (det->x < 0 || det->x > frame->cols || det->y < 0 ||
              det->y > frame->rows)) {
    // Drawn offscreen, so the preview would look identical to no detection at all.
    // Say so rather than letting a broken backend pass for a silent model.
    // Raw values are the model's own output before the frame/imgsz scaling, so they
    // say whether the graph emitted nonsense or the scaling introduced it.
    LOGE("detection outside frame: x=%.0f y=%.0f raw=(%.1f,%.1f) w=%.0f h=%.0f "
         "conf=%.2f (frame %dx%d, imgsz %d)",
         det->x, det->y, det->x * imgsz / static_cast<float>(frame->cols),
         det->y * imgsz / static_cast<float>(frame->rows), det->w, det->h, det->conf,
         frame->cols, frame->rows, imgsz);
  }

  if (det) {
    cv::rectangle(*frame,
                  cv::Rect(static_cast<int>(det->x - det->w / 2),
                           static_cast<int>(det->y - det->h / 2),
                           static_cast<int>(det->w), static_cast<int>(det->h)),
                  cv::Scalar(0, 255, 0), 2);
    cv::putText(*frame, cv::format("conf=%.2f", det->conf),
                cv::Point(static_cast<int>(det->x - det->w / 2),
                          static_cast<int>(det->y - det->h / 2) - 8),
                cv::FONT_HERSHEY_SIMPLEX, 0.6, cv::Scalar(0, 255, 0), 2);
  } else {
    cv::putText(*frame, "no detection", cv::Point(10, 60),
                cv::FONT_HERSHEY_SIMPLEX, 0.7, cv::Scalar(0, 0, 255), 2);
  }

  if (!det)
    return env->NewFloatArray(0);

  const float values[5] = {det->conf, det->x, det->y, det->w, det->h};
  jfloatArray result = env->NewFloatArray(5);
  env->SetFloatArrayRegion(result, 0, 5, values);
  return result;
}

} // extern "C"
