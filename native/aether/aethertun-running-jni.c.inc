/*
 * RØH JNI extension for the pinned Aether/HEV bridge.
 *
 * Appended to upstream scripts/aethertun-jni.c by
 * scripts/build-aether-runtime.sh so the JNI method shares the upstream
 * translation unit's private mutex and running flag.
 */
JNIEXPORT jboolean JNICALL
Java_studio_cluvex_aether_core_TProxyService_isRunning(JNIEnv *env, jclass clazz)
{
    bool value;

    (void)env;
    (void)clazz;

    pthread_mutex_lock(&lock);
    value = running;
    pthread_mutex_unlock(&lock);

    return value ? JNI_TRUE : JNI_FALSE;
}
