
ABIS_SIMPLE= x86 x86_64 armeabi-v7a arm64-v8a

all: build-all

build-all: \
	build-aap-lv2 \
	get-sfizz-deps \
	patch-sfizz \
	build-java

patch-sfizz: external/sfizz/patch.stamp

external/sfizz/patch.stamp:
	cd external/sfizz && \
		patch -i ../../sfizz-android.patch -p1 && \
		touch patch.stamp || exit 1

## downloads

get-sfizz-deps: dependencies/sfizz-deps/dist/stamp

dependencies/sfizz-deps/dist/stamp: android-libsndfile-binaries.zip androidaudioplugin-debug.aar
	unzip android-libsndfile-binaries.zip -d dependencies/sfizz-deps/
	unzip androidaudioplugin-debug.aar -d dependencies/androidaudioplugin-aar
	./rewrite-pkg-config-paths.sh sfizz-deps
	for a in $(ABIS_SIMPLE) ; do \
		mkdir -p aap-sfizz/src/main/jniLibs/$$a ; \
		cp -R dependencies/sfizz-deps/dist/$$a/lib/*.so aap-sfizz/src/main/jniLibs/$$a ; \
	done
	touch dependencies/sfizz-deps/dist/stamp

android-libsndfile-binaries.zip:
	wget https://github.com/atsushieno/android-native-audio-builders/releases/download/r8.3/android-libsndfile-binaries.zip

androidaudioplugin-debug.aar:
	wget https://github.com/atsushieno/android-audio-plugin-framework/releases/download/v0.5.5/androidaudioplugin-debug.aar

## Build utility

build-aap-lv2:
	cd external/aap-lv2 && make build-non-app

build-java:
	ANDROID_SDK_ROOT=$(ANDROID_SDK_ROOT) ./gradlew build
