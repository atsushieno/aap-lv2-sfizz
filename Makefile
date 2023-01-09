
ABIS_SIMPLE= x86 x86_64 armeabi-v7a arm64-v8a

all: build-all

build-all: \
	build-aap-lv2 \
	patch-sfizz \
	build-java

patch-sfizz: external/sfizz/patch.stamp
	if [ ! -L aap-sfizz/src/main/cpp ] ; then \
	cd aap-sfizz/src/main && ln -s ../../../external/sfizz cpp ; \
	else echo "symlink already exists" ; \
	fi

external/sfizz/patch.stamp:
	cd external/sfizz && \
		patch -i ../../sfizz-android.patch -p1 && \
		touch patch.stamp || exit 1

## Build utility

build-aap-lv2:
	cd external/aap-lv2 && make build-non-app

build-java:
	ANDROID_SDK_ROOT=$(ANDROID_SDK_ROOT) ./gradlew build

## update AAP metadata

update-metadata:
	cd external/aap-lv2 && make build-lv2-importer
	external/aap-lv2/tools/aap-import-lv2-metadata/build/aap-import-lv2-metadata aap-sfizz/src/main/assets/lv2 aap-sfizz/src/main/res/xml
