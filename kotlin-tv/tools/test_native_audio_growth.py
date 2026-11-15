"""Host regression: real FFmpeg AAC decode with a preserving buffer-growth callback.
Requires macOS Homebrew ffmpeg/openjdk@21 and clang++. Does not emulate Android ART.
Run directly; not part of Python unittest discovery.
"""
if __name__ == "__main__":
    from pathlib import Path
    import subprocess
    import tempfile
    import shutil
    
    root = Path(__file__).resolve().parents[1]
    work = Path(tempfile.mkdtemp(prefix="hongguotv-native-audio-"))
    (work / "android").mkdir()
    (work / "android/log.h").write_text("#pragma once\n#define ANDROID_LOG_ERROR 6\n#define ANDROID_LOG_DEBUG 3\ninline int __android_log_print(int, const char*, const char*, ...) { return 0; }\n")
    subprocess.run(["ffmpeg", "-v", "error", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=0.15", "-ac", "2", "-c:a", "aac", "-f", "adts", str(work / "sample.aac"), "-y"], check=True)
    s=(root / 'decoder-ffmpeg/src/main/jni/ffmpeg_jni.cc').read_text()
    a=s.index('uint8_t *GrowOutputBufferCallback::operator()');b=s.index('AUDIO_DECODER_FUNC(jint, ffmpegGetChannelCount,',a)
    s=s[:a]+'''#include <vector>
    static std::vector<uint8_t> storage;
    uint8_t *GrowOutputBufferCallback::operator()(int n) const { storage.resize(n); return storage.data(); }
    '''+s[b:]
    s+='''
    #include <fstream>
    #include <iterator>
    #include <cstdio>
    int main() {
     std::ifstream in("/tmp/issue55-native/sample.aac", std::ios::binary);
     std::vector<uint8_t> data((std::istreambuf_iterator<char>(in)), {});
     int size=data.size(); data.resize(size+AV_INPUT_BUFFER_PADDING_SIZE,0);
     auto run=[&](int capacity) {
      storage.assign(capacity,0);
      auto *ctx=createContext(nullptr,avcodec_find_decoder(AV_CODEC_ID_AAC),nullptr,false,0,0);
      AVPacket *p=av_packet_alloc(); p->data=data.data(); p->size=size;
      int n=decodePacket(ctx,p,storage.data(),capacity,{nullptr,nullptr,nullptr});
      printf("capacity=%d output=%d\\n",capacity,n);
      av_packet_free(&p);releaseContext(ctx);
      if(n<0) exit(2); storage.resize(n);return storage;
     };
     auto large=run(1000000);auto small=run(1);
     if(large!=small){puts("FAIL: buffer growth changes decoded PCM");return 1;}
     puts("PASS: decoded PCM matches");
    }
    '''
    s = s.replace("/tmp/issue55-native/sample.aac", str(work / "sample.aac"))
    (work / "test.cc").write_text(s)
    ffmpeg = subprocess.check_output(["brew", "--prefix", "ffmpeg"], text=True).strip()
    jdk = subprocess.check_output(["brew", "--prefix", "openjdk@21"], text=True).strip() + "/libexec/openjdk.jdk/Contents/Home"
    subprocess.run(["clang++", "-std=c++17", "-fsanitize=address,undefined", "-I"+str(work), "-I"+ffmpeg+"/include", "-I"+jdk+"/include", "-I"+jdk+"/include/darwin", str(work / "test.cc"), "-L"+ffmpeg+"/lib", "-lavcodec", "-lavutil", "-lswresample", "-o", str(work / "test")], check=True)
    subprocess.run([str(work / "test")], check=True)
    shutil.rmtree(work)
