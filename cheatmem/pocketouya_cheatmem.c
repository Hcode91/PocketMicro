// Memory access for PocketMicro's opt-in cheat menu, loaded into the game's own process.
//
// Android stops apps opening /proc/self/mem, but process_vm_readv/process_vm_writev on the
// app's own pid are always allowed (the kernel skips its ptrace checks for the same process).
// A bad address returns an error instead of crashing the game, unlike reading it directly.
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/uio.h>
#include <unistd.h>

// Called through syscall(): bionic only declares the wrappers from API 23, and the helper targets API 21.
static ssize_t vm_read(struct iovec *local, struct iovec *remote) {
    return (ssize_t)syscall(__NR_process_vm_readv, (long)getpid(), local, 1UL, remote, 1UL, 0UL);
}
static ssize_t vm_write(struct iovec *local, struct iovec *remote) {
    return (ssize_t)syscall(__NR_process_vm_writev, (long)getpid(), local, 1UL, remote, 1UL, 0UL);
}

#define EXPORT __attribute__((visibility("default")))

// Reads up to length bytes; returns how many were read before the first unreadable page (0..length).
static jint copy_out(uint64_t address, void *buffer, jint length) {
    jint done = 0;
    while (done < length) {
        struct iovec local = {(char *)buffer + done, (size_t)(length - done)};
        struct iovec remote = {(void *)(uintptr_t)(address + (uint64_t)done), (size_t)(length - done)};
        ssize_t n = vm_read(&local, &remote);
        if (n <= 0) break;
        done += (jint)n;
    }
    return done;
}

EXPORT JNIEXPORT jint JNICALL
Java_io_github_hcode91_pocketouya_bridge_MemoryScanner_nativeRead(JNIEnv *env, jclass cls, jlong address, jbyteArray target, jint length) {
    (void)cls;
    if (length <= 0 || length > (*env)->GetArrayLength(env, target)) return 0;
    void *buffer = malloc((size_t)length);
    if (!buffer) return 0;
    jint n = copy_out((uint64_t)address, buffer, length);
    if (n > 0) (*env)->SetByteArrayRegion(env, target, 0, n, (const jbyte *)buffer);
    free(buffer);
    return n;
}

EXPORT JNIEXPORT jboolean JNICALL
Java_io_github_hcode91_pocketouya_bridge_MemoryScanner_nativeWrite(JNIEnv *env, jclass cls, jlong address, jbyteArray source, jint length) {
    (void)cls;
    if (length <= 0 || length > 8 || length > (*env)->GetArrayLength(env, source)) return JNI_FALSE;
    jbyte bytes[8];
    (*env)->GetByteArrayRegion(env, source, 0, length, bytes);
    struct iovec local = {bytes, (size_t)length};
    struct iovec remote = {(void *)(uintptr_t)(uint64_t)address, (size_t)length};
    return vm_write(&local, &remote) == length ? JNI_TRUE : JNI_FALSE;
}
