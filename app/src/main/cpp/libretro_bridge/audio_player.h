#ifndef AUDIO_PLAYER_H
#define AUDIO_PLAYER_H

#ifdef __cplusplus
#include <oboe/Oboe.h>
#include <vector>
#include <mutex>

class AudioPlayer : public oboe::AudioStreamDataCallback {
public:
    static AudioPlayer& getInstance() {
        static AudioPlayer instance;
        return instance;
    }

    void start(int target_sample_rate);
    void stop();
    
    void pushAudioBatch(const int16_t* data, size_t frames);

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* audioStream, void* audioData, int32_t numFrames) override;

private:
    AudioPlayer() = default;
    
    std::shared_ptr<oboe::AudioStream> stream;
    
    std::vector<int16_t> buffer;
    size_t readPos = 0;
    size_t writePos = 0;
    size_t availableFrames = 0;
    const size_t MAX_FRAMES = 16384; 
    
    std::mutex bufferMutex;
};

extern "C" {
#endif

// C API for libretro bridge
void audio_player_start(int sample_rate);
void audio_player_stop(void);
void audio_player_push_batch(const int16_t* data, size_t frames);

#ifdef __cplusplus
}
#endif

#endif // AUDIO_PLAYER_H

