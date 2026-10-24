LOCAL_PATH := $(call my-dir)

ROOT_DIR     := $(LOCAL_PATH)/../..
LIBRETRO_DIR := $(ROOT_DIR)/libretro
INCFLAGS     := -I$(ROOT_DIR)/GLideN64/src/GLideNHQ/inc

# Reset flags that the common makefile doesn't properly handle
platform     := android
COREFLAGS    :=
COREASMFLAGS :=
CORELDLIBS   :=
DYNAFLAGS    :=
GLES         :=
GLFLAGS      :=
HAVE_NEON    :=
SOURCES_C    :=
SOURCES_CXX  :=
SOURCES_ASM  :=
SOURCES_NASM :=
ANDROID      := 1
AWK          ?= awk
STRINGS      ?= $(NDK_ROOT)/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strings
TR           ?= tr
HAVE_PARALLEL_RSP ?= 0
HAVE_PARALLEL_RDP ?= 0
HAVE_THR_AL       ?= 0
LLE               ?= 0
LOCAL_SHORT_COMMANDS := true
WITH_DYNAREC :=

ifeq ($(TARGET_ARCH_ABI),armeabi-v7a)
  WITH_DYNAREC := arm
  HAVE_NEON := 1
  LLE = 1
  HAVE_PARALLEL_RSP = 1
  HAVE_PARALLEL_RDP = 1
  HAVE_THR_AL = 1
else ifeq ($(TARGET_ARCH_ABI),arm64-v8a)
  WITH_DYNAREC := aarch64
  HAVE_NEON := 1
  LLE = 1
  HAVE_PARALLEL_RSP = 1
  HAVE_PARALLEL_RDP = 1
  HAVE_THR_AL = 1
else ifeq ($(TARGET_ARCH_ABI),x86)
  WITH_DYNAREC := x86
  COREASMFLAGS := -f elf -d ELF_TYPE -DPIC
  COREFLAGS := -fPIC
else ifeq ($(TARGET_ARCH_ABI),x86_64)
  WITH_DYNAREC := x86_64
endif

ifeq ($(GLES3),1)
  GLLIB := -lGLESv3
else
  GLES  := 1
  GLLIB := -lGLESv2
endif

include $(ROOT_DIR)/Makefile.common

# The dynarec's assembly reads struct offsets from headers generated out of
# asm_defines.c's compiled object.
ASM_DEFINES_SRC  := $(CORE_DIR)/src/asm_defines/asm_defines.c
AWK_DEST_DIR     := $(NDK_APP_OUT)/local/$(TARGET_ARCH_ABI)/asm_defines
ASM_DEFINES_NASM := $(AWK_DEST_DIR)/asm_defines_nasm.h
ASM_DEFINES_GAS  := $(AWK_DEST_DIR)/asm_defines_gas.h

COREFLAGS += -D__LIBRETRO__ -DOS_ANDROID -DUSE_FILE32API -DM64P_PLUGIN_API -DM64P_CORE_PROTOTYPES -D_ENDUSER_RELEASE -DSINC_LOWER_QUALITY -DMUPENPLUSAPI -DTXFILTER_LIB -D__VEC4_OPT $(INCFLAGS) $(GLFLAGS) $(DYNAFLAGS) -DANDROID -DEGL_EGLEXT_PROTOTYPES -DHAVE_POSIX_MEMALIGN=1

ifeq ($(LLE), 1)
   COREFLAGS += -DHAVE_LLE
endif

ifeq ($(HAVE_PARALLEL_RSP), 1)
   COREFLAGS += -DHAVE_MMAP=1
endif

GIT_VERSION := " $(shell git rev-parse --short HEAD || echo unknown)"
ifneq ($(GIT_VERSION)," unknown")
  COREFLAGS += -DGIT_VERSION=\"$(GIT_VERSION)\"
endif

NIN64_ENABLE_THINLTO ?= 1
NIN64_ABI_ENABLE_THINLTO := $(NIN64_ENABLE_THINLTO)
NIN64_C_RELEASE_FLAGS := -O3 -DNDEBUG -fsigned-char -fno-strict-aliasing -fomit-frame-pointer -fvisibility=hidden
NIN64_CPP_RELEASE_FLAGS := -fvisibility-inlines-hidden
NIN64_LD_RELEASE_FLAGS := -O3 -Wl,--gc-sections -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384

ifeq ($(TARGET_ARCH_ABI),x86)
  NIN64_ABI_ENABLE_THINLTO := 0
else ifeq ($(TARGET_ARCH_ABI),x86_64)
  NIN64_ABI_ENABLE_THINLTO := 0
endif

ifeq ($(NIN64_ABI_ENABLE_THINLTO),1)
  NIN64_C_RELEASE_FLAGS += -flto=thin
  NIN64_CPP_RELEASE_FLAGS += -flto=thin
  NIN64_LD_RELEASE_FLAGS += -flto=thin
endif

# asm_defines.c gets a module of its own, built with the core's flags but
# without LTO: a ThinLTO object is bitcode, which hides the offsets from strings.
include $(CLEAR_VARS)
LOCAL_MODULE           := retro_asm_defines
LOCAL_SRC_FILES        := $(ASM_DEFINES_SRC)
LOCAL_CFLAGS           := $(filter-out -flto=thin,$(NIN64_C_RELEASE_FLAGS)) $(CFLAGS) $(COREFLAGS)
LOCAL_ARM_MODE         := arm
LOCAL_CONLYFLAGS       := -std=gnu11
include $(BUILD_STATIC_LIBRARY)

ASM_DEFINES_OBJ := $(LOCAL_OBJS_DIR)/$(call get-object-name,$(ASM_DEFINES_SRC))

include $(CLEAR_VARS)
LOCAL_MODULE           := retro
LOCAL_SRC_FILES        := $(filter-out $(ASM_DEFINES_SRC),$(SOURCES_CXX) $(SOURCES_C)) $(SOURCES_ASM) $(SOURCES_NASM)
LOCAL_C_INCLUDES       := $(AWK_DEST_DIR)
LOCAL_ASMFLAGS         := $(COREASMFLAGS) -I$(AWK_DEST_DIR)/
LOCAL_CPPFLAGS         := -std=gnu++11 $(NIN64_CPP_RELEASE_FLAGS) $(CXXFLAGS) $(COREFLAGS)
LOCAL_CFLAGS           := $(NIN64_C_RELEASE_FLAGS) $(CFLAGS) $(COREFLAGS)
LOCAL_LDFLAGS          := -Wl,-version-script=$(LIBRETRO_DIR)/link.T $(NIN64_LD_RELEASE_FLAGS)
LOCAL_LDLIBS           := -llog -lEGL $(GLLIB) $(CORELDLIBS)
# Linked only so ndk-build builds it: nothing references its symbols.
LOCAL_STATIC_LIBRARIES := retro_asm_defines
LOCAL_CPP_FEATURES     := exceptions
LOCAL_ARM_MODE         := arm
LOCAL_ARM_NEON         := true
LOCAL_CONLYFLAGS       := -std=gnu11

include $(BUILD_SHARED_LIBRARY)

$(ASM_DEFINES_NASM): $(ASM_DEFINES_OBJ) $(CORE_DIR)/tools/gen_asm_defines.awk
	@mkdir -p $(dir $@)
	$(STRINGS) "$<" | $(TR) -d '\r' | $(AWK) \
		-v dest_dir="$(dir $@)" \
		-f $(CORE_DIR)/tools/gen_asm_defines.awk

$(ASM_DEFINES_GAS): $(ASM_DEFINES_NASM)

$(foreach src,$(SOURCES_ASM),$(eval $(LOCAL_OBJS_DIR)/$(call get-object-name,$(src)) : $(ASM_DEFINES_GAS)))
$(foreach src,$(SOURCES_NASM),$(eval $(LOCAL_OBJS_DIR)/$(call get-object-name,$(src)) : $(ASM_DEFINES_NASM)))
