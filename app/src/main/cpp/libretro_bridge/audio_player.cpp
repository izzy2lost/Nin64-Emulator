#include "audio_player.h"
#include <android/log.h>

#define LOG_TAG "OboeAudio"

void AudioPlayer::start(int target_sample_rate) {
    buffer.resize(MAX_FRAMES * 2, 0); // 2 channels
    readPos = 0;
    writePos = 0;
    availableFrames = 0;
    
    oboe::AudioStreamBuilder builder;
    builder.setFormat(oboe::AudioFormat::I16);
    builder.setChannelCount(2);
    builder.setSampleRate(target_sample_rate);
    builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
    builder.setSharingMode(oboe::SharingMode::Exclusive);
    builder.setDataCallback(this);

    oboe::Result result = builder.openStream(stream);
    if (result == oboe::Result::OK) {
        stream->requestStart();
        __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "AudioStream started");
    } else {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Failed to start audio: %s", oboe::convertToText(result));
    }
}

void AudioPlayer::stop() {
    if (stream) {
        stream->requestStop();
        stream->close();
        stream.reset();
        __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "AudioStream stopped");
    }
}

void AudioPlayer::pushAudioBatch(const int16_t* data, size_t frames) {
    std::lock_guard<std::mutex> lock(bufferMutex);
    
    for (size_t i = 0; i < frames; ++i) {
        if (availableFrames >= MAX_FRAMES) break; 
        
        buffer[writePos * 2] = data[i * 2];
        buffer[writePos * 2 + 1] = data[i * 2 + 1];
        
        writePos = (writePos + 1) % MAX_FRAMES;
        availableFrames++;
    }
}

oboe::DataCallbackResult AudioPlayer::onAudioReady(oboe::AudioStream* audioStream, void* audioData, int32_t numFrames) {
    int16_t* outData = static_cast<int16_t*>(audioData);
    std::lock_guard<std::mutex> lock(bufferMutex);
    
    for (int32_t i = 0; i < numFrames; ++i) {
        if (availableFrames > 0) {
            outData[i * 2] = buffer[readPos * 2];
            outData[i * 2 + 1] = buffer[readPos * 2 + 1];
            readPos = (readPos + 1) % MAX_FRAMES;
            availableFrames--;
        } else {
            outData[i * 2] = 0;
            outData[i * 2 + 1] = 0;
        }
    }
    return oboe::DataCallbackResult::Continue;
}

extern "C" {
    void audio_player_start(int sample_rate) {
        AudioPlayer::getInstance().start(sample_rate);
    }
    void audio_player_stop(void) {
        AudioPlayer::getInstance().stop();
    }
    void audio_player_push_batch(const int16_t* data, size_t frames) {
        AudioPlayer::getInstance().pushAudioBatch(data, frames);
    }
}
