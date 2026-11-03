#import <Foundation/Foundation.h>
#import <MediaPlayer/MediaPlayer.h>
#include <jni.h>

static JavaVM *vm;
static jclass receiver;
static jmethodID onButton;
static NSMutableArray *targets;
// AppKit/MediaPlayer must always run on the macOS main thread, never the AWT EDT.
static void mainSync(dispatch_block_t block) {
    if ([NSThread isMainThread]) block(); else dispatch_sync(dispatch_get_main_queue(), block);
}
static void sendButton(jint button) {
    JNIEnv *env = nullptr;
    bool attached = vm->GetEnv((void **)&env, JNI_VERSION_1_8) == JNI_EDETACHED;
    if (attached && vm->AttachCurrentThread((void **)&env, nullptr) != JNI_OK) return;
    if (env && receiver) {
        env->CallStaticVoidMethod(receiver, onButton, button);
        if (env->ExceptionCheck()) env->ExceptionClear();
    }
    if (attached) vm->DetachCurrentThread();
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_music_bitchord_desktop_DesktopMacMedia_nativeStart(JNIEnv *env, jobject instance) {
    env->GetJavaVM(&vm);
    jclass local = env->GetObjectClass(instance);
    receiver = (jclass)env->NewGlobalRef(local);
    onButton = env->GetStaticMethodID(local, "onButton", "(I)V");
    env->DeleteLocalRef(local);
    if (!onButton) { env->DeleteGlobalRef(receiver); receiver = nullptr; return JNI_FALSE; }
    mainSync(^{
        MPRemoteCommandCenter *center = MPRemoteCommandCenter.sharedCommandCenter;
        targets = [NSMutableArray array];
        NSArray<MPRemoteCommand *> *commands = @[center.playCommand, center.pauseCommand,
            center.nextTrackCommand, center.previousTrackCommand];
        for (NSUInteger i = 0; i < commands.count; ++i) {
            MPRemoteCommand *command = commands[i];
            command.enabled = YES;
            id token = [command addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent *event) {
                sendButton((jint)i);
                return MPRemoteCommandHandlerStatusSuccess;
            }];
            [targets addObject:@[command, token]];
        }
    });
    return JNI_TRUE;
}
extern "C" JNIEXPORT void JNICALL
Java_com_music_bitchord_desktop_DesktopMacMedia_nativeStop(JNIEnv *env, jobject) {
    mainSync(^{
        for (NSArray *pair in targets) [(MPRemoteCommand *)pair[0] removeTarget:pair[1]];
        targets = nil;
        MPNowPlayingInfoCenter.defaultCenter.nowPlayingInfo = nil;
        MPNowPlayingInfoCenter.defaultCenter.playbackState = MPNowPlayingPlaybackStateStopped;
    });
    if (receiver) env->DeleteGlobalRef(receiver);
    receiver = nullptr;
}
static NSString *string(JNIEnv *env, jstring value) {
    const jchar *chars = env->GetStringChars(value, nullptr);
    NSString *result = [[NSString alloc] initWithCharacters:(const unichar *)chars length:env->GetStringLength(value)];
    env->ReleaseStringChars(value, chars);
    return result;
}
extern "C" JNIEXPORT void JNICALL
Java_com_music_bitchord_desktop_DesktopMacMedia_nativeUpdate(JNIEnv *env, jobject,
    jstring title, jstring artist, jboolean playing, jlong position, jlong duration) {
    NSString *track = string(env, title), *performer = string(env, artist);
    // Synchronous dispatch bounds ownership and ensures stop cannot be followed by stale updates.
    mainSync(^{
        MPNowPlayingInfoCenter *info = MPNowPlayingInfoCenter.defaultCenter;
        info.nowPlayingInfo = @{MPMediaItemPropertyTitle: track, MPMediaItemPropertyArtist: performer,
            MPMediaItemPropertyPlaybackDuration: @((double)duration / 1000.0),
            MPNowPlayingInfoPropertyElapsedPlaybackTime: @((double)position / 1000.0),
            MPNowPlayingInfoPropertyPlaybackRate: @(playing ? 1.0 : 0.0)};
        info.playbackState = playing ? MPNowPlayingPlaybackStatePlaying : MPNowPlayingPlaybackStatePaused;
    });
}
