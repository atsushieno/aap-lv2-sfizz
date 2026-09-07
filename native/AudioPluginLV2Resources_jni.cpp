#include "aap-lv2-internal.h"
#include "sfizz_resources.h"

#include <aap/core/host/audio-plugin-host.h>
#include <android/log.h>
#include <jni.h>
#include <sys/mman.h>
#include <sys/stat.h>

#include <limits>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
JavaVM* javaVm = nullptr;
jobject resourceProvider = nullptr;
std::mutex providerMutex;

struct Mapping {
    void* address;
    size_t length;

    Mapping(void* address, size_t length) : address(address), length(length) {}
    Mapping(const Mapping&) = delete;
    Mapping& operator=(const Mapping&) = delete;

    ~Mapping() {
        if (address != MAP_FAILED)
            munmap(address, length);
    }
};

struct Pack {
    std::string entry;
    std::vector<std::string> names;
    std::vector<std::unique_ptr<Mapping>> mappings;
    std::vector<sfizz_resource_t> resources;
    jobject snapshot = nullptr;
    ~Pack() {
        if (!snapshot) return;
        JNIEnv* env = nullptr;
        const bool attached = javaVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK;
        if (attached && javaVm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        env->DeleteGlobalRef(snapshot);
        if (attached) javaVm->DetachCurrentThread();
    }
};

std::string stringValue(JNIEnv* env, jstring value) {
    if (!value)
        throw std::runtime_error("Null resource string");
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars)
        throw std::runtime_error("Cannot read resource string");
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

// ResourceSet serializes these requests. Retain mappings, not open descriptors,
// for the lifetime of the instrument and its background decoder jobs.
int openIndexedResource(void* owner, const char* name, const void** data, size_t* size) {
    auto* pack = static_cast<Pack*>(owner);
    JNIEnv* env = nullptr;
    const bool attached = javaVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK;
    if (attached && javaVm->AttachCurrentThread(&env, nullptr) != JNI_OK) return 0;
    if (env->PushLocalFrame(24) < 0) {
        if (attached) javaVm->DetachCurrentThread();
        return 0;
    }
    jobject asset = nullptr;
    jmethodID close = nullptr;
    bool success = false;
    try {
        auto snapshotClass = env->GetObjectClass(pack->snapshot);
        auto open = env->GetMethodID(snapshotClass, "openResource", "(Ljava/lang/String;)Landroid/content/res/AssetFileDescriptor;");
        asset = env->CallObjectMethod(pack->snapshot, open, env->NewStringUTF(name));
        if (env->ExceptionCheck() || !asset) throw std::runtime_error("Document open failed");
        auto afdClass = env->GetObjectClass(asset);
        close = env->GetMethodID(afdClass, "close", "()V");
        const jlong offset = env->CallLongMethod(asset, env->GetMethodID(afdClass, "getStartOffset", "()J"));
        const jlong length = env->CallLongMethod(asset, env->GetMethodID(afdClass, "getDeclaredLength", "()J"));
        auto pfd = env->CallObjectMethod(asset, env->GetMethodID(afdClass, "getParcelFileDescriptor", "()Landroid/os/ParcelFileDescriptor;"));
        auto pfdClass = env->GetObjectClass(pfd);
        const jint fd = env->CallIntMethod(pfd, env->GetMethodID(pfdClass, "getFd", "()I"));
        struct stat st {};
        const long pageSize = sysconf(_SC_PAGESIZE);
        if (env->ExceptionCheck() || pageSize <= 0 || offset < 0 || length <= 0 ||
            fstat(fd, &st) || !S_ISREG(st.st_mode) || offset > st.st_size || length > st.st_size - offset)
            throw std::runtime_error("Invalid document range");
        const size_t displacement = offset % pageSize;
        if (static_cast<uint64_t>(length) > std::numeric_limits<size_t>::max() - displacement)
            throw std::runtime_error("Document mapping overflow");
        const size_t mappedLength = static_cast<size_t>(length) + displacement;
        void* address = mmap(nullptr, mappedLength, PROT_READ, MAP_PRIVATE, fd, offset - displacement);
        if (address == MAP_FAILED) throw std::runtime_error("Cannot map document");
        auto mapping = std::make_unique<Mapping>(address, mappedLength);
        pack->mappings.push_back(std::move(mapping));
        *data = static_cast<const char*>(address) + displacement;
        *size = static_cast<size_t>(length);
        success = true;
    } catch (const std::exception& error) {
        __android_log_print(ANDROID_LOG_ERROR, "AAP.SFZ", "Opening document %s failed: %s", name, error.what());
    } catch (...) {}
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (asset && close) env->CallVoidMethod(asset, close);
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->PopLocalFrame(nullptr);
    if (attached) javaVm->DetachCurrentThread();
    return success;
}
} // namespace

extern "C" __attribute__((visibility("default"))) int
aap_sfizz_resource_open(void*, const char* identity, sfizz_resource_pack_t* result) {
    if (!javaVm || !identity || !result)
        return 0;

    JNIEnv* env = nullptr;
    const bool attached = javaVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK;
    if (attached && javaVm->AttachCurrentThread(&env, nullptr) != JNI_OK)
        return 0;
    if (env->PushLocalFrame(32) < 0) {
        if (attached)
            javaVm->DetachCurrentThread();
        return 0;
    }

    jobject snapshot = nullptr;
    jmethodID closeMethod = nullptr;
    bool success = false;
    try {
        jobject provider;
        {
            std::lock_guard<std::mutex> lock(providerMutex);
            provider = env->NewLocalRef(resourceProvider);
        }
        if (!provider)
            throw std::runtime_error("No resource provider");

        const jclass providerClass = env->GetObjectClass(provider);
        const jmethodID openMethod = env->GetMethodID(
                providerClass,
                "open",
                "(Ljava/lang/String;)Lorg/androidaudioplugin/samples/aap_sfizz/AudioPluginLV2ResourceBridge$Snapshot;");
        if (!openMethod)
            throw std::runtime_error("Invalid resource provider");
        snapshot = env->CallObjectMethod(provider, openMethod, env->NewStringUTF(identity));
        if (env->ExceptionCheck() || !snapshot)
            throw std::runtime_error("Resource provider failed");

        const jclass snapshotClass = env->GetObjectClass(snapshot);
        closeMethod = env->GetMethodID(snapshotClass, "close", "()V");
        const auto entry = static_cast<jstring>(env->GetObjectField(
                snapshot, env->GetFieldID(snapshotClass, "entry", "Ljava/lang/String;")));
        const auto names = static_cast<jobjectArray>(env->GetObjectField(
                snapshot, env->GetFieldID(snapshotClass, "names", "[Ljava/lang/String;")));
        const auto descriptors = static_cast<jobjectArray>(env->GetObjectField(
                snapshot,
                env->GetFieldID(snapshotClass, "descriptors", "[Landroid/content/res/AssetFileDescriptor;")));
        if (env->ExceptionCheck() || !names || !descriptors)
            throw std::runtime_error("Invalid resource snapshot");

        const jsize count = env->GetArrayLength(names);
        const bool lazy = env->GetBooleanField(snapshot, env->GetFieldID(snapshotClass, "lazy", "Z"));
        if (count < 1 || count > 65536 || (!lazy && count != env->GetArrayLength(descriptors)))
            throw std::runtime_error("Invalid resource count");

        auto pack = std::make_unique<Pack>();
        pack->entry = stringValue(env, entry);
        pack->names.reserve(count);
        pack->mappings.reserve(count);
        pack->resources.reserve(count);

        if (lazy) {
            pack->snapshot = env->NewGlobalRef(snapshot);
            if (!pack->snapshot) throw std::runtime_error("Cannot retain document index");
            for (jsize i = 0; i < count; ++i) {
                auto name = static_cast<jstring>(env->GetObjectArrayElement(names, i));
                pack->names.push_back(stringValue(env, name));
                env->DeleteLocalRef(name);
                pack->resources.push_back({pack->names.back().c_str(), nullptr, 0});
            }
            *result = {2, pack->entry.c_str(), pack->resources.data(), pack->resources.size(),
                pack.get(), [](void* owner) { delete static_cast<Pack*>(owner); }, openIndexedResource};
            pack.release();
            // The index stays alive through the global reference; it owns no open files.
            env->PopLocalFrame(nullptr);
            if (attached) javaVm->DetachCurrentThread();
            return 1;
        }

        const jclass afdClass = env->FindClass("android/content/res/AssetFileDescriptor");
        const jclass pfdClass = env->FindClass("android/os/ParcelFileDescriptor");
        const jmethodID getOffset = env->GetMethodID(afdClass, "getStartOffset", "()J");
        const jmethodID getLength = env->GetMethodID(afdClass, "getDeclaredLength", "()J");
        const jmethodID getPfd = env->GetMethodID(
                afdClass, "getParcelFileDescriptor", "()Landroid/os/ParcelFileDescriptor;");
        const jmethodID getFd = env->GetMethodID(pfdClass, "getFd", "()I");
        const long pageSize = sysconf(_SC_PAGESIZE);
        if (pageSize <= 0)
            throw std::runtime_error("Invalid page size");

        uint64_t totalSize = 0;
        for (jsize i = 0; i < count; ++i) {
            if (env->PushLocalFrame(8) < 0)
                throw std::runtime_error("Cannot allocate JNI frame");
            try {
                const auto name = static_cast<jstring>(env->GetObjectArrayElement(names, i));
                pack->names.push_back(stringValue(env, name));
                const jobject descriptor = env->GetObjectArrayElement(descriptors, i);
                const jlong offset = env->CallLongMethod(descriptor, getOffset);
                const jlong length = env->CallLongMethod(descriptor, getLength);
                const jobject pfd = env->CallObjectMethod(descriptor, getPfd);
                const jint fd = env->CallIntMethod(pfd, getFd);
                struct stat fileStat {};
                if (env->ExceptionCheck() || offset < 0 || length <= 0 || fstat(fd, &fileStat) ||
                    !S_ISREG(fileStat.st_mode) || offset > fileStat.st_size ||
                    length > fileStat.st_size - offset ||
                    static_cast<uint64_t>(length) > std::numeric_limits<size_t>::max() ||
                    (totalSize += static_cast<uint64_t>(length)) > (uint64_t{8} << 30))
                    throw std::runtime_error("Resource is not a bounded regular-file range");

                const size_t displacement = static_cast<size_t>(offset % pageSize);
                if (static_cast<uint64_t>(length) >
                    std::numeric_limits<size_t>::max() - displacement)
                    throw std::runtime_error("Resource mapping overflow");
                const size_t mappedLength = static_cast<size_t>(length) + displacement;
                void* address = mmap(
                        nullptr,
                        mappedLength,
                        PROT_READ,
                        MAP_PRIVATE,
                        fd,
                        offset - static_cast<jlong>(displacement));
                if (address == MAP_FAILED)
                    throw std::runtime_error("Cannot map resource");
                pack->mappings.emplace_back(std::make_unique<Mapping>(address, mappedLength));
                pack->resources.push_back({
                        pack->names.back().c_str(),
                        static_cast<const char*>(address) + displacement,
                        static_cast<size_t>(length),
                });
                env->PopLocalFrame(nullptr);
            } catch (...) {
                env->PopLocalFrame(nullptr);
                throw;
            }
        }

        *result = {
                1,
                pack->entry.c_str(),
                pack->resources.data(),
                pack->resources.size(),
                pack.get(),
                [](void* owner) { delete static_cast<Pack*>(owner); },
        };
        pack.release();
        success = true;
    } catch (const std::exception& error) {
        __android_log_print(
                ANDROID_LOG_ERROR, "AAP.SFZ", "Opening resource '%s' failed: %s", identity, error.what());
        if (env->ExceptionCheck())
            env->ExceptionClear();
    } catch (...) {
        __android_log_print(ANDROID_LOG_ERROR, "AAP.SFZ", "Opening resource '%s' failed", identity);
        if (env->ExceptionCheck())
            env->ExceptionClear();
    }

    if (snapshot && closeMethod)
        env->CallVoidMethod(snapshot, closeMethod);
    if (env->ExceptionCheck())
        env->ExceptionClear();
    env->PopLocalFrame(nullptr);
    if (attached)
        javaVm->DetachCurrentThread();
    return success;
}

namespace {
const sfizz_resource_host_t resourceHost {nullptr, aap_sfizz_resource_open};
}

extern "C" JNIEXPORT void JNICALL
Java_org_androidaudioplugin_samples_aap_1sfizz_AudioPluginLV2ResourceBridge_initialize(
        JNIEnv* env, jobject, jobject provider) {
    std::lock_guard<std::mutex> lock(providerMutex);
    env->GetJavaVM(&javaVm);
    if (resourceProvider)
        env->DeleteGlobalRef(resourceProvider);
    resourceProvider = env->NewGlobalRef(provider);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_androidaudioplugin_samples_aap_1sfizz_AudioPluginLV2ResourceBridge_load(
        JNIEnv* env, jobject, jlong nativeService, jint instanceId, jstring identity) {
    if (!nativeService || !identity)
        return JNI_FALSE;
    try {
        auto* service = reinterpret_cast<aap::PluginService*>(nativeService);
        auto* instance = service->getLocalInstance(instanceId);
        if (!instance || !instance->getPlugin())
            return JNI_FALSE;
        auto* context = static_cast<aaplv2bridge::AAPLV2PluginContext*>(
                instance->getPlugin()->plugin_specific);
        if (!context || !context->instance)
            return JNI_FALSE;
        const auto* api = static_cast<const sfizz_resource_interface_t*>(
                lilv_instance_get_extension_data(context->instance, SFIZZ_RESOURCE_INTERFACE));
        if (!api || !api->set_host || !api->load)
            return JNI_FALSE;
        void* handle = lilv_instance_get_handle(context->instance);
        api->set_host(handle, &resourceHost);
        const auto resourceIdentity = stringValue(env, identity);
        const bool loaded = api->load(handle, resourceIdentity.c_str());
        __android_log_print(loaded ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR, "AAP.SFZ",
                "Resource load %s: %s (%d regions)", loaded ? "succeeded" : "failed",
                resourceIdentity.c_str(), api->region_count ? api->region_count(handle) : -1);
        return loaded ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}
