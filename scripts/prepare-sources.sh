#!/usr/bin/env bash
set -euo pipefail

if [ ! -f external/sfizz/patch.stamp ]; then
  patch -d external/sfizz -i ../../sfizz-android.patch -p1
  touch external/sfizz/patch.stamp
fi

if [ ! -e app/src/main/cpp ]; then
  ln -s ../../../external/sfizz app/src/main/cpp
fi
