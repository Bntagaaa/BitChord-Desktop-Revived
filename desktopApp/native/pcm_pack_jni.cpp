#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <limits>

#if defined(__SSE2__) || defined(_M_X64)
#include <emmintrin.h>
#endif

namespace {

#if defined(__SSE2__) || defined(_M_X64)
__m128 ScalePcm(const float* input, __m128 gain, __m128 scale) {
  const auto sample = _mm_mul_ps(_mm_loadu_ps(input), gain);
  const auto clipped = _mm_max_ps(_mm_set1_ps(-1.0f), _mm_min_ps(_mm_set1_ps(1.0f), sample));
  return _mm_mul_ps(_mm_and_ps(clipped, _mm_cmpord_ps(sample, sample)), scale);
}
#endif

template <class Word>
void PackInteger(const float* input, unsigned char* output, jint count, float gain) {
  constexpr auto limit = std::numeric_limits<Word>::max();
  const float scale = static_cast<float>(limit);
  jint i = 0;
#if defined(__SSE2__) || defined(_M_X64)
  const auto volume = _mm_set1_ps(gain);
  const auto multiplier = _mm_set1_ps(scale);
  if constexpr (sizeof(Word) == 2) {
    for (; count - i >= 8; i += 8) {
      const auto first = _mm_cvttps_epi32(ScalePcm(input + i, volume, multiplier));
      const auto second = _mm_cvttps_epi32(ScalePcm(input + i + 4, volume, multiplier));
      _mm_storeu_si128(reinterpret_cast<__m128i*>(output + i * sizeof(Word)), _mm_packs_epi32(first, second));
    }
  } else {
    for (; count - i >= 4; i += 4) {
      const auto sample = ScalePcm(input + i, volume, multiplier);
      // Kotlin saturates +2^31 to INT_MAX. CVTTPS2DQ returns INT_MIN there.
      const auto upper = _mm_castps_si128(_mm_cmpge_ps(sample, multiplier));
      const auto truncated = _mm_cvttps_epi32(sample);
      const auto packed = _mm_or_si128(_mm_and_si128(upper, _mm_set1_epi32(limit)),
                                       _mm_andnot_si128(upper, truncated));
      _mm_storeu_si128(reinterpret_cast<__m128i*>(output + i * sizeof(Word)), packed);
    }
  }
#endif
  for (; i < count; ++i) {
    const float sample = std::clamp(input[i] * gain, -1.0f, 1.0f) * scale;
    const Word word = std::isnan(sample) ? 0 :
        sample >= scale ? limit : static_cast<Word>(sample);
    // Desktop PulseAudio targets little-endian x86-64.
    std::memcpy(output + i * sizeof(word), &word, sizeof(word));
  }
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_music_bitchord_desktop_DesktopNativePcm_nativePack(
    JNIEnv* env, jclass, jfloatArray input, jint count, jfloat gain,
    jint bytesPerSample, jboolean floating, jobject target) {
  auto* output = static_cast<unsigned char*>(env->GetDirectBufferAddress(target));
  if (output == nullptr || count <= 0 || count > env->GetArrayLength(input) ||
      (bytesPerSample != 2 && bytesPerSample != 4) || (floating && bytesPerSample != 4) ||
      env->GetDirectBufferCapacity(target) < static_cast<jlong>(count) * bytesPerSample) return JNI_FALSE;

  auto* samples = static_cast<jfloat*>(env->GetPrimitiveArrayCritical(input, nullptr));
  if (samples == nullptr) return JNI_FALSE;
  if (floating) {
    for (jint i = 0; i < count; ++i) {
      const float sample = samples[i] * gain;
      std::memcpy(output + i * sizeof(sample), &sample, sizeof(sample));
    }
  } else if (bytesPerSample == 4) {
    PackInteger<int32_t>(samples, output, count, gain);
  } else {
    PackInteger<int16_t>(samples, output, count, gain);
  }
  env->ReleasePrimitiveArrayCritical(input, samples, JNI_ABORT);
  return JNI_TRUE;
}
