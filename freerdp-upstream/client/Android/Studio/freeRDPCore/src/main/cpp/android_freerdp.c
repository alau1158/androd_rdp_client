/*
   Android JNI Client Layer

   Copyright 2010-2012 Marc-Andre Moreau <marcandre.moreau@gmail.com>
   Copyright 2013 Thincast Technologies GmbH, Author: Martin Fleisz
   Copyright 2013 Thincast Technologies GmbH, Author: Armin Novak
   Copyright 2015 Bernhard Miklautz <bernhard.miklautz@thincast.com>
   Copyright 2016 Thincast Technologies GmbH
   Copyright 2016 Armin Novak <armin.novak@thincast.com>

   This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
   If a copy of the MPL was not distributed with this file, You can obtain one at
   http://mozilla.org/MPL/2.0/.
*/

#include <freerdp/config.h>
#include <freerdp/error.h>

#include <locale.h>

#include <jni.h>
#include <stdarg.h>
#include <android/log.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <dlfcn.h>
#include <errno.h>

#include <winpr/assert.h>
#include <winpr/wlog.h>
#include <winpr/image.h>

#include <freerdp/graphics.h>
#include <freerdp/codec/rfx.h>
#include <freerdp/gdi/gdi.h>
#include <freerdp/gdi/gfx.h>
#include <freerdp/client/rdpei.h>
#include <freerdp/client/rdpgfx.h>
#include <freerdp/client/cliprdr.h>
#include <freerdp/codec/h264.h>
#include <freerdp/codec/video.h>
#include <freerdp/channels/channels.h>
#include <freerdp/client/channels.h>
#include <freerdp/client/cmdline.h>
#include <freerdp/constants.h>
#include <freerdp/locale/keyboard.h>
#include <freerdp/primitives.h>
#include <freerdp/version.h>
#include <freerdp/settings.h>
#include <freerdp/utils/signal.h>

#define DEX_S(x) ((x) ? (x) : "(null)")

static UINT32 g_LastConnectError = 0;
static char g_DexLogPath[4096] = { 0 };

/* Writes straight to disk with an explicit flush each time. FreeRDP's built in
   file appender buffers and never flushes on Android, which made it useless. */
static void dexrdp_log(const char* fmt, ...)
{
	char buffer[2048];
	va_list args;

	va_start(args, fmt);
	vsnprintf(buffer, sizeof(buffer), fmt, args);
	va_end(args);

	__android_log_print(ANDROID_LOG_WARN, "DexRdpNative", "%s", buffer);

	if (g_DexLogPath[0] != '\0')
	{
		FILE* f = fopen(g_DexLogPath, "a");

		if (f)
		{
			fprintf(f, "%s\n", buffer);
			fflush(f);
			fclose(f);
		}
	}
}

/* Forwards FreeRDP/WinPR log messages into our own flushing logger. */
static BOOL dexrdp_wlog_message(const wLogMessage* msg)
{
	if (!msg)
		return FALSE;

	dexrdp_log("[freerdp]%s%s", msg->PrefixString ? msg->PrefixString : "",
	           msg->TextString ? msg->TextString : "");
	return TRUE;
}

#include <android/bitmap.h>

#include "android_jni_callback.h"
#include "android_jni_utils.h"
#include "android_cliprdr.h"
#include "android_disp.h"
#include "android_rail.h"
#include "android_freerdp_jni.h"

#if defined(WITH_GPROF)
#include "jni/prof.h"
#endif

#define TAG CLIENT_TAG("android")

/* Defines the JNI version supported by this library. */
#define FREERDP_JNI_VERSION FREERDP_VERSION_FULL

static jclass gJavaActivityClass;
static jmethodID gOnPointerSetMethod;
static jmethodID gOnRailWindowUpdateMethod;

static UINT android_UpdateWindowFromSurface(RdpgfxClientContext* context, gdiGfxSurface* surface);

static void android_OnChannelConnectedEventHandler(void* context,
                                                   const ChannelConnectedEventArgs* e)
{
	rdpSettings* settings;
	androidContext* afc;

	if (!context || !e)
	{
		WLog_FATAL(TAG, "(context=%p, EventArgs=%p", context, (void*)e);
		return;
	}

	afc = (androidContext*)context;
	settings = afc->common.context.settings;

	if (strcmp(e->name, CLIPRDR_SVC_CHANNEL_NAME) == 0)
	{
		android_cliprdr_init(afc, (CliprdrClientContext*)e->pInterface);
	}
	else if (strcmp(e->name, DISP_DVC_CHANNEL_NAME) == 0)
	{
		android_disp_init(afc, (DispClientContext*)e->pInterface);
	}
	else if (strcmp(e->name, RAIL_SVC_CHANNEL_NAME) == 0)
	{
		android_rail_init(afc, (RailClientContext*)e->pInterface);
	}
	else if (strcmp(e->name, RDPGFX_DVC_CHANNEL_NAME) == 0)
	{
		freerdp_client_OnChannelConnectedEventHandler(context, e);
		RdpgfxClientContext* gfx = (RdpgfxClientContext*)e->pInterface;
		if (gfx)
			gfx->UpdateWindowFromSurface = android_UpdateWindowFromSurface;
	}
	else
		freerdp_client_OnChannelConnectedEventHandler(context, e);
}

static void android_OnChannelDisconnectedEventHandler(void* context,
                                                      const ChannelDisconnectedEventArgs* e)
{
	rdpSettings* settings;
	androidContext* afc;

	if (!context || !e)
	{
		WLog_FATAL(TAG, "(context=%p, EventArgs=%p", context, (void*)e);
		return;
	}

	afc = (androidContext*)context;
	settings = afc->common.context.settings;

	if (strcmp(e->name, CLIPRDR_SVC_CHANNEL_NAME) == 0)
	{
		android_cliprdr_uninit(afc, (CliprdrClientContext*)e->pInterface);
	}
	else if (strcmp(e->name, DISP_DVC_CHANNEL_NAME) == 0)
	{
		android_disp_uninit(afc, (DispClientContext*)e->pInterface);
	}
	else if (strcmp(e->name, RAIL_SVC_CHANNEL_NAME) == 0)
	{
		android_rail_uninit(afc, (RailClientContext*)e->pInterface);
	}
	else
		freerdp_client_OnChannelDisconnectedEventHandler(context, e);
}

static BOOL android_begin_paint(rdpContext* context)
{
	return TRUE;
}

static BOOL android_end_paint(rdpContext* context)
{
	HGDI_WND hwnd;
	int ninvalid;
	rdpGdi* gdi;
	HGDI_RGN cinvalid;
	int x1, y1, x2, y2;
	androidContext* ctx = (androidContext*)context;
	rdpSettings* settings;

	if (!ctx || !context->instance)
		return FALSE;

	settings = context->settings;

	if (!settings)
		return FALSE;

	gdi = context->gdi;

	if (!gdi || !gdi->primary || !gdi->primary->hdc)
		return FALSE;

	hwnd = ctx->common.context.gdi->primary->hdc->hwnd;

	if (!hwnd)
		return FALSE;

	ninvalid = hwnd->ninvalid;

	if (ninvalid < 1)
		return TRUE;

	cinvalid = hwnd->cinvalid;

	if (!cinvalid)
		return FALSE;

	x1 = cinvalid[0].x;
	y1 = cinvalid[0].y;
	x2 = cinvalid[0].x + cinvalid[0].w;
	y2 = cinvalid[0].y + cinvalid[0].h;

	for (int i = 0; i < ninvalid; i++)
	{
		x1 = MIN(x1, cinvalid[i].x);
		y1 = MIN(y1, cinvalid[i].y);
		x2 = MAX(x2, cinvalid[i].x + cinvalid[i].w);
		y2 = MAX(y2, cinvalid[i].y + cinvalid[i].h);
	}

	freerdp_callback("OnGraphicsUpdate", "(JIIII)V", (jlong)context->instance, x1, y1, x2 - x1,
	                 y2 - y1);

	hwnd->invalid->null = TRUE;
	hwnd->ninvalid = 0;
	return TRUE;
}

static BOOL android_desktop_resize(rdpContext* context)
{
	WINPR_ASSERT(context);
	WINPR_ASSERT(context->settings);
	WINPR_ASSERT(context->instance);

	const UINT32 width = freerdp_settings_get_uint32(context->settings, FreeRDP_DesktopWidth);
	const UINT32 height = freerdp_settings_get_uint32(context->settings, FreeRDP_DesktopHeight);

	if (context->gdi && !gdi_resize(context->gdi, width, height))
		return FALSE;

	freerdp_callback("OnGraphicsResize", "(JIII)V", (jlong)context->instance, width, height,
	                 freerdp_settings_get_uint32(context->settings, FreeRDP_ColorDepth));
	return TRUE;
}

static BOOL android_pre_connect(freerdp* instance)
{
	WINPR_ASSERT(instance);
	WINPR_ASSERT(instance->context);

	rdpSettings* settings = instance->context->settings;

	if (!settings)
		return FALSE;

	int rc = PubSub_SubscribeChannelConnected(instance->context->pubSub,
	                                          android_OnChannelConnectedEventHandler);

	if (rc != CHANNEL_RC_OK)
	{
		WLog_ERR(TAG, "Could not subscribe to connect event handler [%08X]", rc);
		return FALSE;
	}

	rc = PubSub_SubscribeChannelDisconnected(instance->context->pubSub,
	                                         android_OnChannelDisconnectedEventHandler);

	if (rc != CHANNEL_RC_OK)
	{
		WLog_ERR(TAG, "Could not subscribe to disconnect event handler [%08X]", rc);
		return FALSE;
	}

	freerdp_callback("OnPreConnect", "(J)V", (jlong)instance);
	return TRUE;
}

typedef struct
{
	rdpPointer pointer;
	size_t size;
	void* data;
} androidPointer;

static BOOL android_Pointer_New(rdpContext* context, rdpPointer* pointer)
{
	WINPR_ASSERT(context);
	WINPR_ASSERT(pointer);
	WINPR_ASSERT(context->gdi);

	androidPointer* ptr = (androidPointer*)pointer;
	if (!ptr)
		return FALSE;

	ptr->size = 4ULL * pointer->width * pointer->height;
	ptr->data = winpr_aligned_malloc(ptr->size, 16);
	if (!ptr->data)
		return FALSE;

	if (!freerdp_image_copy_from_pointer_data(
	        ptr->data, PIXEL_FORMAT_BGRA32, 0, 0, 0, pointer->width, pointer->height,
	        pointer->xorMaskData, pointer->lengthXorMask, pointer->andMaskData,
	        pointer->lengthAndMask, pointer->xorBpp, &context->gdi->palette))
	{
		winpr_aligned_free(ptr->data);
		ptr->data = nullptr;
		return FALSE;
	}

	return TRUE;
}

static void android_Pointer_Free(rdpContext* context, rdpPointer* pointer)
{
	WINPR_UNUSED(context);
	androidPointer* ptr = (androidPointer*)pointer;

	if (ptr)
	{
		winpr_aligned_free(ptr->data);
		ptr->data = nullptr;
	}
}

static BOOL android_Pointer_Set(rdpContext* context, rdpPointer* pointer)
{
	WINPR_ASSERT(context);
	WINPR_ASSERT(pointer);

	androidPointer* ptr = (androidPointer*)pointer;
	if (!ptr->data)
		return FALSE;

	const jsize nPixels = (jsize)(pointer->width * pointer->height);

	JNIEnv* env = nullptr;
	jboolean attached = jni_attach_thread(&env);

	if (!gJavaActivityClass || !gOnPointerSetMethod)
		goto done;

	jintArray pixels = (*env)->NewIntArray(env, nPixels);
	if (!pixels)
		goto done;

	(*env)->SetIntArrayRegion(env, pixels, 0, nPixels, (const jint*)ptr->data);
	(*env)->CallStaticVoidMethod(env, gJavaActivityClass, gOnPointerSetMethod,
	                             (jlong)context->instance, pixels, (jint)pointer->width,
	                             (jint)pointer->height, (jint)pointer->xPos, (jint)pointer->yPos);
	(*env)->DeleteLocalRef(env, pixels);
done:
	if (attached)
		jni_detach_thread();

	return TRUE;
}

static BOOL android_Pointer_SetPosition(rdpContext* context, UINT32 x, UINT32 y)
{
	WINPR_ASSERT(context);

	return TRUE;
}

static BOOL android_Pointer_SetNull(rdpContext* context)
{
	WINPR_ASSERT(context);

	freerdp_callback("OnPointerSetNull", "(J)V", (jlong)context->instance);
	return TRUE;
}

static BOOL android_Pointer_SetDefault(rdpContext* context)
{
	WINPR_ASSERT(context);

	freerdp_callback("OnPointerSetDefault", "(J)V", (jlong)context->instance);
	return TRUE;
}

static UINT android_UpdateWindowFromSurface(RdpgfxClientContext* context, gdiGfxSurface* surface)
{
	if (!context || !surface)
		return CHANNEL_RC_OK;

	rdpGdi* gdi = (rdpGdi*)context->custom;
	if (!gdi || !gdi->context)
		return CHANNEL_RC_OK;

	UINT32 width = surface->mappedWidth ? surface->mappedWidth : surface->width;
	if (width > surface->width)
		width = surface->width;

	UINT32 height = surface->mappedHeight ? surface->mappedHeight : surface->height;
	if (height > surface->height)
		height = surface->height;

	if (width == 0 || height == 0)
		return CHANNEL_RC_OK;

	JNIEnv* env = nullptr;
	jboolean attached = jni_attach_thread(&env);
	if (!gJavaActivityClass || !gOnRailWindowUpdateMethod)
		goto done;

	const jsize nPixels = (jsize)(width * height);
	jintArray pixels = (*env)->NewIntArray(env, nPixels);
	if (!pixels)
		goto done;

	jint* dst = (*env)->GetIntArrayElements(env, pixels, nullptr);
	if (dst)
	{
		const BOOL rc = freerdp_image_copy((BYTE*)dst, surface->format, width * 4ull, 0, 0, width,
		                                   height, surface->data, surface->format,
		                                   surface->scanline, 0, 0, nullptr, FREERDP_FLIP_NONE);

		/* Force coloured pixels opaque (ARGB_8888 would otherwise blend the active window's
		 * frame away), but keep transparent black so menu corners/shadows stay see-through. */
		const size_t total = (size_t)width * height;
		for (size_t i = 0; i < total; i++)
		{
			const UINT32 px = (UINT32)dst[i];
			if ((px & 0x00FFFFFFu) != 0)
				dst[i] = (jint)(px | 0xFF000000u);
		}
		(*env)->ReleaseIntArrayElements(env, pixels, dst, 0);
		if (!rc)
			goto done;
	}

	freerdp* inst = gdi->context->instance;
	(*env)->CallStaticVoidMethod(env, gJavaActivityClass, gOnRailWindowUpdateMethod, (jlong)inst,
	                             (jlong)surface->windowId, (jint)width, (jint)height, pixels);
	(*env)->DeleteLocalRef(env, pixels);
done:
	if (attached)
		jni_detach_thread();
	return CHANNEL_RC_OK;
}

static BOOL android_register_pointer(rdpGraphics* graphics)
{
	rdpPointer pointer = WINPR_C_ARRAY_INIT;

	if (!graphics)
		return FALSE;

	pointer.size = sizeof(androidPointer);
	pointer.New = android_Pointer_New;
	pointer.Free = android_Pointer_Free;
	pointer.Set = android_Pointer_Set;
	pointer.SetNull = android_Pointer_SetNull;
	pointer.SetDefault = android_Pointer_SetDefault;
	pointer.SetPosition = android_Pointer_SetPosition;
	graphics_register_pointer(graphics, &pointer);
	return TRUE;
}

/* Keep in sync with LibFreeRDP.EXPERIMENTAL_*. */
#define ANDROID_EXPERIMENTAL_REMOTEAPP 0
#define ANDROID_EXPERIMENTAL_CAMERA 1

static BOOL android_post_connect(freerdp* instance)
{
	WINPR_ASSERT(instance);
	WINPR_ASSERT(instance->context);

	rdpUpdate* update = instance->context->update;
	WINPR_ASSERT(update);

	rdpSettings* settings = instance->context->settings;
	WINPR_ASSERT(settings);

	if (freerdp_settings_get_bool(settings, FreeRDP_RemoteApplicationMode) &&
	    !freerdp_callback_bool_result("OnExperimentalFeature", "(JI)Z", (jlong)instance,
	                                  ANDROID_EXPERIMENTAL_REMOTEAPP))
		return FALSE;

	if (freerdp_dynamic_channel_collection_find(settings, "rdpecam") &&
	    !freerdp_callback_bool_result("OnExperimentalFeature", "(JI)Z", (jlong)instance,
	                                  ANDROID_EXPERIMENTAL_CAMERA))
		return FALSE;

	if (!gdi_init(instance, PIXEL_FORMAT_RGBX32))
		return FALSE;

	if (!android_register_pointer(instance->context->graphics))
		return FALSE;

	update->BeginPaint = android_begin_paint;
	update->EndPaint = android_end_paint;
	update->DesktopResize = android_desktop_resize;
	freerdp_callback("OnSettingsChanged", "(JIII)V", (jlong)instance,
	                 freerdp_settings_get_uint32(settings, FreeRDP_DesktopWidth),
	                 freerdp_settings_get_uint32(settings, FreeRDP_DesktopHeight),
	                 freerdp_settings_get_uint32(settings, FreeRDP_ColorDepth));
	freerdp_callback("OnConnectionSuccess", "(J)V", (jlong)instance);
	return TRUE;
}

static void android_post_disconnect(freerdp* instance)
{
	freerdp_callback("OnDisconnecting", "(J)V", (jlong)instance);
	gdi_free(instance);
}

static void android_post_final_disconnect(freerdp* instance)
{
	WINPR_ASSERT(instance);
	WINPR_ASSERT(instance->context);

	PubSub_UnsubscribeChannelConnected(instance->context->pubSub,
	                                   android_OnChannelConnectedEventHandler);
	PubSub_UnsubscribeChannelDisconnected(instance->context->pubSub,
	                                      android_OnChannelDisconnectedEventHandler);
}

static BOOL android_authenticate_int(freerdp* instance, char** username, char** password,
                                     char** domain, const char* cb_name)
{
	JNIEnv* env;
	jboolean attached = jni_attach_thread(&env);
	jobject jstr1 = create_string_builder(env, *username);
	jobject jstr2 = create_string_builder(env, *domain);
	jobject jstr3 = create_string_builder(env, *password);
	jboolean res;
	res = freerdp_callback_bool_result(cb_name,
	                                   "(JLjava/lang/StringBuilder;"
	                                   "Ljava/lang/StringBuilder;"
	                                   "Ljava/lang/StringBuilder;)Z",
	                                   (jlong)instance, jstr1, jstr2, jstr3);

	if (res == JNI_TRUE)
	{
		// read back string values
		free(*username);
		*username = get_string_from_string_builder(env, jstr1);
		free(*domain);
		*domain = get_string_from_string_builder(env, jstr2);
		free(*password);
		*password = get_string_from_string_builder(env, jstr3);
	}

	if (attached == JNI_TRUE)
		jni_detach_thread();

	return ((res == JNI_TRUE) ? TRUE : FALSE);
}

static BOOL android_authenticate_ex(freerdp* instance, char** username, char** password,
                                    char** domain, rdp_auth_reason reason)
{
	switch (reason)
	{
		case AUTH_NLA:
		case AUTH_TLS:
		case AUTH_RDP:
			return android_authenticate_int(instance, username, password, domain, "OnAuthenticate");
		case GW_AUTH_HTTP:
		case GW_AUTH_RDG:
		case GW_AUTH_RPC:
			return android_authenticate_int(instance, username, password, domain,
			                                "OnGatewayAuthenticate");
		default:
			return FALSE;
	}
}

static DWORD android_verify_certificate_ex(freerdp* instance, const char* host, UINT16 port,
                                           const char* common_name, const char* subject,
                                           const char* issuer, const char* fingerprint, DWORD flags)
{
	WLog_DBG(TAG, "Certificate details [%s:%" PRIu16 ":", host, port);
	WLog_DBG(TAG, "\tSubject: %s", subject);
	WLog_DBG(TAG, "\tIssuer: %s", issuer);
	WLog_DBG(TAG, "\tThumbprint: %s", fingerprint);
	WLog_DBG(TAG,
	         "The above X.509 certificate could not be verified, possibly because you do not have "
	         "the CA certificate in your certificate store, or the certificate has expired."
	         "Please look at the OpenSSL documentation on how to add a private CA to the store.\n");
	JNIEnv* env;
	jboolean attached = jni_attach_thread(&env);
	jstring jstr0 = (*env)->NewStringUTF(env, host);
	jstring jstr1 = (*env)->NewStringUTF(env, common_name);
	jstring jstr2 = (*env)->NewStringUTF(env, subject);
	jstring jstr3 = (*env)->NewStringUTF(env, issuer);
	jstring jstr4 = (*env)->NewStringUTF(env, fingerprint);
	jint res = freerdp_callback_int_result("OnVerifyCertificateEx",
	                                       "(JLjava/lang/String;JLjava/lang/String;Ljava/lang/"
	                                       "String;Ljava/lang/String;Ljava/lang/String;J)I",
	                                       (jlong)instance, jstr0, (jlong)port, jstr1, jstr2, jstr3,
	                                       jstr4, (jlong)flags);

	if (attached == JNI_TRUE)
		jni_detach_thread();

	return res;
}

static DWORD android_verify_changed_certificate_ex(freerdp* instance, const char* host, UINT16 port,
                                                   const char* common_name, const char* subject,
                                                   const char* issuer, const char* new_fingerprint,
                                                   const char* old_subject, const char* old_issuer,
                                                   const char* old_fingerprint, DWORD flags)
{
	JNIEnv* env;
	jboolean attached = jni_attach_thread(&env);
	jstring jhost = (*env)->NewStringUTF(env, host);
	jstring jstr0 = (*env)->NewStringUTF(env, common_name);
	jstring jstr1 = (*env)->NewStringUTF(env, subject);
	jstring jstr2 = (*env)->NewStringUTF(env, issuer);
	jstring jstr3 = (*env)->NewStringUTF(env, new_fingerprint);
	jstring jstr4 = (*env)->NewStringUTF(env, old_subject);
	jstring jstr5 = (*env)->NewStringUTF(env, old_issuer);
	jstring jstr6 = (*env)->NewStringUTF(env, old_fingerprint);
	jint res =
	    freerdp_callback_int_result("OnVerifyChangedCertificateEx",
	                                "(JLjava/lang/String;JLjava/lang/String;Ljava/lang/"
	                                "String;Ljava/lang/String;Ljava/lang/String;"
	                                "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;J)I",
	                                (jlong)instance, jhost, (jlong)port, jstr0, jstr1, jstr2, jstr3,
	                                jstr4, jstr5, jstr6, (jlong)flags);

	if (attached == JNI_TRUE)
		jni_detach_thread();

	return res;
}

static int android_freerdp_run(freerdp* instance)
{
	WINPR_ASSERT(instance);

	DWORD status = WAIT_FAILED;
	HANDLE handles[MAXIMUM_WAIT_OBJECTS];
	HANDLE inputEvent = nullptr;
	rdpContext* context = instance->context;
	WINPR_ASSERT(context);

	const rdpSettings* settings = context->settings;

	inputEvent = android_get_handle(instance);

	while (!freerdp_shall_disconnect_context(context))
	{
		DWORD count = 0;

		handles[count++] = inputEvent;

		DWORD tmp = freerdp_get_event_handles(context, &handles[count], 64 - count);

		if (tmp == 0)
		{
			dexrdp_log("run: freerdp_get_event_handles returned 0 err=%u",
			           (unsigned)GetLastError());
			break;
		}

		count += tmp;
		status = WaitForMultipleObjects(count, handles, FALSE, INFINITE);

		if (status == WAIT_FAILED)
		{
			dexrdp_log("run: WaitForMultipleObjects FAILED err=%u", (unsigned)GetLastError());
			break;
		}

		if (!freerdp_check_event_handles(context))
		{
			if (!client_auto_reconnect(instance))
			{
				dexrdp_log("run: check_event_handles failed; auto_reconnect FAILED err=%u",
				           (unsigned)GetLastError());
				status = GetLastError();
				break;
			}

			dexrdp_log("run: check_event_handles failed; auto_reconnect engaged");
		}

		if (freerdp_shall_disconnect_context(instance->context))
		{
			dexrdp_log("run: server/client requested DISCONNECT");
			break;
		}

		if (android_check_handle(instance) != TRUE)
		{
			/* A failed input/clipboard event must not terminate the session.
			   Upstream treated it as fatal, which disconnected healthy sessions. */
			dexrdp_log("run: android_check_handle failed (ignored) err=%u",
			           (unsigned)GetLastError());
		}
	}

	dexrdp_log("run: loop exited status=%u shallDisconnect=%d serverErrInfo=%u ultimatum=%d",
	           status, freerdp_shall_disconnect_context(context), freerdp_error_info(instance),
	           freerdp_get_disconnect_ultimatum(context));

disconnect:
	WLog_INFO(TAG, "Prepare shutdown...");

	return status;
}

static DWORD WINAPI android_thread_func(LPVOID param)
{
	DWORD status = ERROR_BAD_ARGUMENTS;
	freerdp* instance = param;

	dexrdp_log("thread: start");

	if (!instance || !instance->context)
	{
		dexrdp_log("thread: instance or context is NULL");
		goto fail;
	}

	{
		rdpSettings* s = instance->context->settings;

		if (s)
		{
			dexrdp_log("settings: host=%s port=%u user=%s domain=%s",
			           DEX_S(freerdp_settings_get_string(s, FreeRDP_ServerHostname)),
			           freerdp_settings_get_uint32(s, FreeRDP_ServerPort),
			           DEX_S(freerdp_settings_get_string(s, FreeRDP_Username)),
			           DEX_S(freerdp_settings_get_string(s, FreeRDP_Domain)));
			dexrdp_log("settings: nla=%d tls=%d rdp=%d ignoreCert=%d size=%ux%u",
			           freerdp_settings_get_bool(s, FreeRDP_NlaSecurity),
			           freerdp_settings_get_bool(s, FreeRDP_TlsSecurity),
			           freerdp_settings_get_bool(s, FreeRDP_RdpSecurity),
			           freerdp_settings_get_bool(s, FreeRDP_IgnoreCertificate),
			           freerdp_settings_get_uint32(s, FreeRDP_DesktopWidth),
			           freerdp_settings_get_uint32(s, FreeRDP_DesktopHeight));
		}
		else
			dexrdp_log("settings: NULL");
	}

	if (freerdp_client_start(instance->context) != CHANNEL_RC_OK)
	{
		dexrdp_log("client_start FAILED");
		goto fail;
	}

	dexrdp_log("client_start ok; connecting...");

	if (!freerdp_connect(instance))
	{
		g_LastConnectError = freerdp_get_last_error(instance->context);
		dexrdp_log("connect FAILED rdpError=%u (%s) winprError=%u", g_LastConnectError,
		           freerdp_get_error_connect_string(g_LastConnectError), GetLastError());
		status = GetLastError();
	}
	else
	{
		dexrdp_log("connect OK");
		status = android_freerdp_run(instance);
		dexrdp_log("run returned %u rdpError=%u", status,
		           freerdp_get_last_error(instance->context));

		if (!freerdp_disconnect(instance))
		{
			dexrdp_log("disconnect FAILED winprError=%u", GetLastError());
			status = GetLastError();
		}
	}

	dexrdp_log("stopping client...");

	if (freerdp_client_stop(instance->context) != CHANNEL_RC_OK)
	{
		dexrdp_log("client_stop FAILED");
		goto fail;
	}

	dexrdp_log("client_stop ok");

fail:
	if ((g_LastConnectError == 0) && instance && instance->context)
	{
		g_LastConnectError = freerdp_get_last_error(instance->context);

		if (g_LastConnectError != 0)
			dexrdp_log("captured rdpError=%u (%s)", g_LastConnectError,
			           freerdp_get_error_connect_string(g_LastConnectError));
	}

	dexrdp_log("session ended status=%08" PRIX32 " rdpError=%u", status, g_LastConnectError);

	if (status == CHANNEL_RC_OK)
		freerdp_callback("OnDisconnected", "(J)V", (jlong)instance);
	else
		freerdp_callback("OnConnectionFailure", "(J)V", (jlong)instance);

	ExitThread(status);
	return status;
}

static BOOL android_client_new(freerdp* instance, rdpContext* context)
{
	WINPR_ASSERT(instance);
	WINPR_ASSERT(context);

	if (!android_event_queue_init(instance))
		return FALSE;

	instance->PreConnect = android_pre_connect;
	instance->PostConnect = android_post_connect;
	instance->PostDisconnect = android_post_disconnect;
	instance->PostFinalDisconnect = android_post_final_disconnect;
	instance->AuthenticateEx = android_authenticate_ex;
	instance->VerifyCertificateEx = android_verify_certificate_ex;
	instance->VerifyChangedCertificateEx = android_verify_changed_certificate_ex;
	instance->LogonErrorInfo = nullptr;
	return TRUE;
}

static void android_client_free(freerdp* instance, rdpContext* context)
{
	if (!context)
		return;

	android_event_queue_uninit(instance);
}

static int RdpClientEntry(RDP_CLIENT_ENTRY_POINTS* pEntryPoints)
{
	WINPR_ASSERT(pEntryPoints);

	ZeroMemory(pEntryPoints, sizeof(RDP_CLIENT_ENTRY_POINTS));

	pEntryPoints->Version = RDP_CLIENT_INTERFACE_VERSION;
	pEntryPoints->Size = sizeof(RDP_CLIENT_ENTRY_POINTS_V1);
	pEntryPoints->GlobalInit = nullptr;
	pEntryPoints->GlobalUninit = nullptr;
	pEntryPoints->ContextSize = sizeof(androidContext);
	pEntryPoints->ClientNew = android_client_new;
	pEntryPoints->ClientFree = android_client_free;
	pEntryPoints->ClientStart = nullptr;
	pEntryPoints->ClientStop = nullptr;
	return 0;
}

JNIEXPORT jlong JNICALL Java_com_freerdp_freerdpcore_services_LibFreeRDP_freerdp_1new(
    JNIEnv* env, jclass cls, jobject context)
{
	jclass contextClass;
	jclass fileClass;
	jobject filesDirObj;
	jmethodID getFilesDirID;
	jmethodID getAbsolutePathID;
	jstring path;
	const char* raw;
	char* envStr;
	RDP_CLIENT_ENTRY_POINTS clientEntryPoints;
	rdpContext* ctx;
#if defined(WITH_GPROF)
	setenv("CPUPROFILE_FREQUENCY", "200", 1);
	monstartup("libfreerdp-android.so");
#endif
	contextClass = (*env)->FindClass(env, JAVA_CONTEXT_CLASS);
	fileClass = (*env)->FindClass(env, JAVA_FILE_CLASS);

	if (!contextClass || !fileClass)
	{
		WLog_FATAL(TAG, "Failed to load class references %s=%p, %s=%p", JAVA_CONTEXT_CLASS,
		           (void*)contextClass, JAVA_FILE_CLASS, (void*)fileClass);
		return (jlong) nullptr;
	}

	getFilesDirID =
	    (*env)->GetMethodID(env, contextClass, "getFilesDir", "()L" JAVA_FILE_CLASS ";");

	if (!getFilesDirID)
	{
		WLog_FATAL(TAG, "Failed to find method ID getFilesDir ()L" JAVA_FILE_CLASS ";");
		return (jlong) nullptr;
	}

	getAbsolutePathID =
	    (*env)->GetMethodID(env, fileClass, "getAbsolutePath", "()Ljava/lang/String;");

	if (!getAbsolutePathID)
	{
		WLog_FATAL(TAG, "Failed to find method ID getAbsolutePath ()Ljava/lang/String;");
		return (jlong) nullptr;
	}

	filesDirObj = (*env)->CallObjectMethod(env, context, getFilesDirID);

	if (!filesDirObj)
	{
		WLog_FATAL(TAG, "Failed to call getFilesDir");
		return (jlong) nullptr;
	}

	path = (*env)->CallObjectMethod(env, filesDirObj, getAbsolutePathID);

	if (!path)
	{
		WLog_FATAL(TAG, "Failed to call getAbsolutePath");
		return (jlong) nullptr;
	}

	raw = (*env)->GetStringUTFChars(env, path, 0);

	if (!raw)
	{
		WLog_FATAL(TAG, "Failed to get C string from java string");
		return (jlong) nullptr;
	}

	envStr = _strdup(raw);
	(*env)->ReleaseStringUTFChars(env, path, raw);

	if (!envStr)
	{
		WLog_FATAL(TAG, "_strdup(%s) failed", raw);
		return (jlong) nullptr;
	}

	if (setenv("HOME", _strdup(envStr), 1) != 0)
	{
		char ebuffer[256] = WINPR_C_ARRAY_INIT;
		WLog_FATAL(TAG, "Failed to set environment HOME=%s %s [%d]", envStr,
		           winpr_strerror(errno, ebuffer, sizeof(ebuffer)), errno);
		return (jlong) nullptr;
	}

	RdpClientEntry(&clientEntryPoints);
	ctx = freerdp_client_context_new(&clientEntryPoints);

	if (!ctx)
		return (jlong) nullptr;

	return (jlong)ctx->instance;
}

JNIEXPORT void JNICALL Java_com_freerdp_freerdpcore_services_LibFreeRDP_freerdp_1free(
    JNIEnv* env, jclass cls, jlong instance)
{
	freerdp* inst = (freerdp*)instance;

	if (inst)
		freerdp_client_context_free(inst->context);

#if defined(WITH_GPROF)
	moncleanup();
#endif
}

JNIEXPORT jstring JNICALL
Java_com_freerdp_freerdpcore_services_LibFreeRDP_freerdp_1get_1last_1error_1string(JNIEnv* env,
                                                                                   jclass cls,
                                                                                   jlong instance)
{
	freerdp* inst = (freerdp*)instance;

	if (!inst || !inst->context)
		return (*env)->NewStringUTF(env, "");

	return (*env)->NewStringUTF(
	    env, freerdp_get_last_error_string(freerdp_get_last_error(inst->context)));
}

JNIEXPORT jboolean JNICALL
Java_com_freerdp_freerdpcore_services_LibFreeRDP_freerdp_1parse_1arguments(JNIEnv* env, jclass cls,
                                                                           jlong instance,
                                                                           jobjectArray arguments)
{
	freerdp* inst = (freerdp*)instance;
	int count;
	char** argv;
	DWORD status;

	if (!inst || !inst->context)
		return JNI_FALSE;

	count = (*env)->GetArrayLength(env, arguments);
	argv = calloc(count, sizeof(char*));

	if (!argv)
		return JNI_TRUE;

	for (int i = 0; i < count; i++)
	{
		jstring str = (jstring)(*env)->GetObjectArrayElement(env, arguments, i);
		const char* raw = (*env)->GetStringUTFChars(env, str, 0);
		argv[i] = _strdup(raw);
		(*env)->ReleaseStringUTFChars(env, str, raw);
	}

	status =
	    freerdp_client_settings_parse_command_line(inst->context->settings, count, argv, FALSE);

	for (int i = 0; i < count; i++)
		free(argv[i]);

	free(argv);
	return (status == 0) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_freerdp_freerdpcore_services_LibFreeRDP_freerdp_1connect(
    JNIEnv* env, jclass cls, jlong instance)
{
	freerdp* inst = (freerdp*)instance;

	if (!inst || !inst->context)
	{
		WLog_FATAL(TAG, "(env=%p, cls=%p, instance=%" PRId64, (void*)env, (void*)cls,
		           (int64_t)instance);
		return JNI_FALSE;
	}

	androidContext* ctx = (androidContext*)inst->context;

	if (!(ctx->thread = CreateThread(nullptr, 0, android_thread_func, inst, 0, nullptr)))
	{
		return JNI_FALSE;
	}

	return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL Java_com_freerdp_freerdpcore_services_LibFreeRDP_freerdp_1disconnect(
    JNIEnv* env, jclass cls, jlong instance)
{
	freerdp* inst = (freerdp*)instance;

	if (!inst || !inst->context || !cls || !env)
	{
		WLog_FATAL(TAG, "(env=%p, cls=%p, instance=%" PRId64, (void*)env, (void*)cls,
		           (int64_t)instance);
		return JNI_FALSE;
	}

	androidContext* ctx = (androidContext*)inst->context;
	ANDROID_EVENT* event = (ANDROID_EVENT*)android_event_disconnect_new();

	if (!event)
		return JNI_FALSE;

	if (!android_push_event(inst, event))
	{
		android_event_free((ANDROID_EVENT*)event);
		return JNI_FALSE;
	}

	if (!freerdp_abort_connect_context(inst->context))
		return JNI_FALSE;

	return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_freerdp_freerdpcore_services_LibFreeRDP_freerdp_1update_1graphics(JNIEnv* env, jclass cls,
                                                                           jlong instance,
                                                                           jobject bitmap, jint x,
                                                                           jint y, jint width,
                                                                           jint height)
{
	UINT32 DstFormat;
	jboolean rc;
	int ret;
	void* pixels;
	AndroidBitmapInfo info;
	freerdp* inst = (freerdp*)instance;
	rdpGdi* gdi;

	if (!env || !cls || !inst)
	{
		WLog_FATAL(TAG, "(env=%p, cls=%p, instance=%" PRId64, (void*)env, (void*)cls,
		           (int64_t)instance);
		return JNI_FALSE;
	}

	gdi = inst->context->gdi;

	if ((ret = AndroidBitmap_getInfo(env, bitmap, &info)) < 0)
	{
		WLog_FATAL(TAG, "AndroidBitmap_getInfo() failed ! error=%d", ret);
		return JNI_FALSE;
	}

	if ((ret = AndroidBitmap_lockPixels(env, bitmap, &pixels)) < 0)
	{
		WLog_FATAL(TAG, "AndroidBitmap_lockPixels() failed ! error=%d", ret);
		return JNI_FALSE;
	}

	rc = JNI_TRUE;

	switch (info.format)
	{
		case ANDROID_BITMAP_FORMAT_RGBA_8888:
			DstFormat = PIXEL_FORMAT_RGBX32;
			break;

		case ANDROID_BITMAP_FORMAT_RGB_565:
			DstFormat = PIXEL_FORMAT_RGB16;
			break;

		case ANDROID_BITMAP_FORMAT_RGBA_4444:
		case ANDROID_BITMAP_FORMAT_A_8:
		case ANDROID_BITMAP_FORMAT_NONE:
		default:
			rc = JNI_FALSE;
			break;
	}

	if (rc)
	{
		rc = freerdp_image_copy(pixels, DstFormat, info.stride, x, y, width, height,
		                        gdi->primary_buffer, gdi->dstFormat, gdi->stride, x, y,
		                        &gdi->palette, FREERDP_FLIP_NONE);
	}

	if ((ret = AndroidBitmap_unlockPixels(env, bitmap)) < 0)
	{
		WLog_FATAL(TAG, "AndroidBitmap_unlockPixels() failed ! error=%d", ret);
		return JNI_FALSE;
	}

	return rc;
}

JNIEXPORT jboolean JNICALL
Java_com_freerdp_freerdpcore_services_LibFreeRDP_freerdp_1send_1key_1event(JNIEnv* env, jclass cls,
                                                                           jlong instance,
                                                                           jint keycode,
                                                                           jboolean down)
{
	DWORD scancode;
	ANDROID_EVENT* event;
	freerdp* inst = (freerdp*)instance;
	scancode = GetVirtualScanCodeFromVirtualKeyCode(keycode, 4);
	int flags = (down == JNI_TRUE) ? KBD_FLAGS_DOWN : KBD_FLAGS_RELEASE;
	flags |= (scancode & KBDEXT) ? KBD_FLAGS_EXTENDED : 0;
	event = (ANDROID_EVENT*)android_event_key_new(flags, scancode & 0xFF);

	if (!event)
		return JNI_FALSE;

	if (!android_push_event(inst, event))
	{
		android_event_free(event);
		return JNI_FALSE;
	}

	WLog_DBG(TAG, "send_key_event: %" PRIu32 ", %d", scancode, flags);
	return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_freerdp_freerdpcore_services_LibFreeRDP_freerdp_1send_1unicodekey_1event(
    JNIEnv* env, jclass cls, jlong instance, jint keycode, jboolean down)
{
	ANDROID_EVENT* event;
	freerdp* inst = (freerdp*)instance;
	UINT16 flags = (down == JNI_TRUE) ? 0 : KBD_FLAGS_RELEASE;
	event = (ANDROID_EVENT*)android_event_unicodekey_new(flags, keycode);

	if (!event)
		return JNI_FALSE;

	if (!android_push_event(inst, event))
	{
		android_event_free(event);
		return JNI_FALSE;
	}

	WLog_DBG(TAG, "send_unicodekey_event: %d", keycode);
	return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_freerdp_freerdpcore_services_LibFreeRDP_freerdp_1is_1unicode_1input_1supported(
    JNIEnv* env, jclass cls, jlong instance)
{
	freerdp* inst = (freerdp*)instance;

	if (!inst || !inst->context || !inst->context->settings)
		return JNI_FALSE;

	if (!freerdp_settings_get_bool(inst->context->settings, FreeRDP_UnicodeInput))
		return JNI_FALSE;

	return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_freerdp_freerdpcore_services_LibFreeRDP_freerdp_1send_1cursor_1event(
    JNIEnv* env, jclass cls, jlong instance, jint x, jint y, jint flags)
{
	ANDROID_EVENT* event;
	freerdp* inst = (freerdp*)instance;
	event = (ANDROID_EVENT*)android_event_cursor_new(flags, x, y);

	if (!event)
		return JNI_FALSE;

	if (!android_push_event(inst, event))
	{
		android_event_free(event);
		return JNI_FALSE;
	}

	WLog_DBG(TAG, "send_cursor_event: (%d, %d), %d", x, y, flags);
	return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_freerdp_freerdpcore_services_LibFreeRDP_freerdp_1send_1extended_1cursor_1event(
    JNIEnv* env, jclass cls, jlong instance, jint x, jint y, jint flags)
{
	ANDROID_EVENT* event;
	freerdp* inst = (freerdp*)instance;
	event = (ANDROID_EVENT*)android_event_cursor_x_new(flags, x, y);

	if (!event)
		return JNI_FALSE;

	if (!android_push_event(inst, event))
	{
		android_event_free(event);
		return JNI_FALSE;
	}

	WLog_DBG(TAG, "send_extended_cursor_event: (%d, %d), %d", x, y, flags);
	return JNI_TRUE;
}

static jboolean android_push_clipboard_event(freerdp* inst, const void* data, size_t data_length,
                                             const char* mimeType)
{
	ANDROID_EVENT* event = (ANDROID_EVENT*)android_event_clipboard_new(data, data_length, mimeType);
	if (!event)
		return JNI_FALSE;
	if (!android_push_event(inst, event))
	{
		android_event_free(event);
		return JNI_FALSE;
	}
	return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_freerdp_freerdpcore_services_LibFreeRDP_freerdp_1send_1clipboard_1data(JNIEnv* env,
                                                                                jclass cls,
                                                                                jlong instance,
                                                                                jstring jdata)
{
	WINPR_UNUSED(cls);
	freerdp* inst = (freerdp*)instance;
	const char* data = jdata != nullptr ? (*env)->GetStringUTFChars(env, jdata, nullptr) : nullptr;
	const size_t data_length = data ? (*env)->GetStringUTFLength(env, jdata) : 0;
	jboolean ret = android_push_clipboard_event(inst, data, data_length, "text/plain");
	WLog_DBG(TAG, "send_clipboard_data: (%s)", data);

	if (data)
		(*env)->ReleaseStringUTFChars(env, jdata, data);

	return ret;
}

static BOOL android_is_image_mime_supported(const char* mimeType)
{
	if (!mimeType)
		return FALSE;
	if (strcmp(mimeType, "image/png") == 0)
		return winpr_image_format_is_supported(WINPR_IMAGE_PNG);
	if (strcmp(mimeType, "image/jpeg") == 0 || strcmp(mimeType, "image/jpg") == 0)
		return winpr_image_format_is_supported(WINPR_IMAGE_JPEG);
	if (strcmp(mimeType, "image/webp") == 0)
		return winpr_image_format_is_supported(WINPR_IMAGE_WEBP);
	return FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_freerdp_freerdpcore_services_LibFreeRDP_freerdp_1send_1clipboard_1image_1data(
    JNIEnv* env, jclass cls, jlong instance, jbyteArray jdata, jstring jmimeType)
{
	WINPR_UNUSED(cls);
	freerdp* inst = (freerdp*)instance;
	jsize data_length = (*env)->GetArrayLength(env, jdata);
	jbyte* data = (*env)->GetByteArrayElements(env, jdata, nullptr);
	const char* mimeType = jmimeType ? (*env)->GetStringUTFChars(env, jmimeType, nullptr) : nullptr;
	jboolean ret = JNI_FALSE;
	if (android_is_image_mime_supported(mimeType))
		ret = android_push_clipboard_event(inst, data, (size_t)data_length, mimeType);

	if (mimeType)
		(*env)->ReleaseStringUTFChars(env, jmimeType, mimeType);
	(*env)->ReleaseByteArrayElements(env, jdata, data, JNI_ABORT);
	return ret;
}

JNIEXPORT jboolean JNICALL
Java_com_freerdp_freerdpcore_services_LibFreeRDP_freerdp_1send_1monitor_1layout(
    JNIEnv* env, jclass cls, jlong instance, jint width, jint height)
{
	WINPR_UNUSED(env);
	WINPR_UNUSED(cls);
	freerdp* inst = (freerdp*)instance;

	if (!inst || !inst->context)
		return JNI_FALSE;

	androidContext* afc = (androidContext*)inst->context;
	return android_disp_send_monitor_layout(afc, (UINT32)width, (UINT32)height) ? JNI_TRUE
	                                                                            : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_freerdp_freerdpcore_services_LibFreeRDP_freerdp_1get_1jni_1version(JNIEnv* env, jclass cls)
{
	return (*env)->NewStringUTF(env, FREERDP_JNI_VERSION);
}

JNIEXPORT jboolean JNICALL
Java_com_freerdp_freerdpcore_services_LibFreeRDP_freerdp_1has_1h264(JNIEnv* env, jclass cls)
{
	H264_CONTEXT* ctx = h264_context_new(FALSE);
	if (!ctx)
		return JNI_FALSE;
	h264_context_free(ctx);
	return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_freerdp_freerdpcore_services_LibFreeRDP_freerdp_1has_1camera_1redirection(JNIEnv* env,
                                                                                   jclass cls)
{
	return freerdp_video_conversion_supported(FREERDP_VIDEO_FORMAT_NV12,
	                                          FREERDP_VIDEO_FORMAT_YUV420P)
	           ? JNI_TRUE
	           : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_freerdp_freerdpcore_services_LibFreeRDP_get_1build_1config(JNIEnv* env, jclass cls)
{
	return (*env)->NewStringUTF(env, freerdp_get_build_config());
}

JNIEXPORT jboolean JNICALL
Java_com_freerdp_freerdpcore_services_LibFreeRDP_enableFileLogging(JNIEnv* env, jclass cls,
                                                                    jstring path)
{
	const char* filename = (*env)->GetStringUTFChars(env, path, NULL);

	if (!filename)
		return JNI_FALSE;

	strncpy(g_DexLogPath, filename, sizeof(g_DexLogPath) - 1);
	g_DexLogPath[sizeof(g_DexLogPath) - 1] = '\0';
	dexrdp_log("---- native logging started ----");

	wLog* root = WLog_GetRoot();

	if (!root)
	{
		(*env)->ReleaseStringUTFChars(env, path, filename);
		return JNI_FALSE;
	}

	if (!WLog_SetLogAppenderType(root, WLOG_APPENDER_CALLBACK))
	{
		(*env)->ReleaseStringUTFChars(env, path, filename);
		return JNI_FALSE;
	}

	wLogAppender* appender = WLog_GetLogAppender(root);

	if (!appender)
	{
		(*env)->ReleaseStringUTFChars(env, path, filename);
		return JNI_FALSE;
	}

	wLogCallbacks callbacks = { 0 };
	callbacks.message = dexrdp_wlog_message;

	BOOL rc = WLog_ConfigureAppender(appender, "callbacks", &callbacks);

	if (rc)
	{
		WLog_SetLogLevel(root, WLOG_DEBUG);
		WLog_INFO(TAG, "DeX RDP logging enabled; FreeRDP %s (%s)",
		          freerdp_get_version_string(), freerdp_get_build_revision());
	}

	(*env)->ReleaseStringUTFChars(env, path, filename);
	return rc ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_freerdp_freerdpcore_services_LibFreeRDP_getLastErrorString(JNIEnv* env, jclass cls,
                                                                    jlong inst)
{
	freerdp* instance = (freerdp*)inst;

	if (!instance || !instance->context)
		return (*env)->NewStringUTF(env, "No active session");

	UINT32 code = freerdp_get_last_error(instance->context);

	if (code == 0)
		code = g_LastConnectError;

	if (code == 0)
		return (*env)->NewStringUTF(env, "No error reported");

	const char* name = freerdp_get_error_connect_name(code);
	const char* info = freerdp_get_error_connect_string(code);

	if (info && *info)
		return (*env)->NewStringUTF(env, info);

	if (name && *name)
		return (*env)->NewStringUTF(env, name);

	return (*env)->NewStringUTF(env, "Unknown error");
}

static void* g_DexUdpLib = NULL;
static const char* (*g_DexUdpVersion)(void) = NULL;

JNIEXPORT jstring JNICALL
Java_com_freerdp_freerdpcore_services_LibFreeRDP_dexrdpUdpVersion(JNIEnv* env, jclass cls)
{
	if (!g_DexUdpLib)
	{
		g_DexUdpLib = dlopen("libdexrdp_udp.so", RTLD_NOW);

		if (g_DexUdpLib)
			g_DexUdpVersion = (const char* (*)(void))dlsym(g_DexUdpLib, "dexrdp_udp_version");

		dexrdp_log("dlopen libdexrdp_udp.so => %p versionFn=%p err=%s", g_DexUdpLib,
		           (void*)g_DexUdpVersion, dlerror() ? dlerror() : "none");
	}

	if (g_DexUdpVersion)
		return (*env)->NewStringUTF(env, g_DexUdpVersion());

	return (*env)->NewStringUTF(env, "not loaded");
}

JNIEXPORT jstring JNICALL
Java_com_freerdp_freerdpcore_services_LibFreeRDP_freerdp_1get_1build_1revision(JNIEnv* env,
                                                                               jclass cls)
{
	return (*env)->NewStringUTF(env, freerdp_get_build_revision());
}

JNIEXPORT jstring JNICALL
Java_com_freerdp_freerdpcore_services_LibFreeRDP_freerdp_1get_1build_1config(JNIEnv* env,
                                                                             jclass cls)
{
	return (*env)->NewStringUTF(env, freerdp_get_build_config());
}

jint JNI_OnLoad(JavaVM* vm, void* reserved)
{
	JNIEnv* env;
	setlocale(LC_ALL, "");
	WLog_DBG(TAG, "Setting up JNI environment...");

	/*
	    if (freerdp_handle_signals() != 0)
	    {
	        WLog_FATAL(TAG, "Failed to register signal handler");
	        return -1;
	    }
	*/
	if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK)
	{
		WLog_FATAL(TAG, "Failed to get the environment");
		return -1;
	}

	// Get SBCEngine activity class
	jclass activityClass = (*env)->FindClass(env, JAVA_LIBFREERDP_CLASS);

	if (!activityClass)
	{
		WLog_FATAL(TAG, "failed to get %s class reference", JAVA_LIBFREERDP_CLASS);
		return -1;
	}

	/* create global reference for class */
	gJavaActivityClass = (*env)->NewGlobalRef(env, activityClass);
	gOnPointerSetMethod =
	    (*env)->GetStaticMethodID(env, gJavaActivityClass, "OnPointerSet", "(J[IIIII)V");
	gOnRailWindowUpdateMethod =
	    (*env)->GetStaticMethodID(env, gJavaActivityClass, "OnRailWindowUpdate", "(JJII[I)V");
	if (!gOnRailWindowUpdateMethod)
	{
		(*env)->ExceptionClear(env);
		WLog_WARN(TAG, "OnRailWindowUpdate method not found, RAIL window display disabled");
	}
	g_JavaVm = vm;
	return init_callback_environment(vm, env);
}

void JNICALL JNI_OnUnload(JavaVM* vm, void* reserved)
{
	JNIEnv* env;
	WLog_DBG(TAG, "Tearing down JNI environment...");

	if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK)
	{
		WLog_FATAL(TAG, "Failed to get the environment");
		return;
	}

	if (gJavaActivityClass)
		(*env)->DeleteGlobalRef(env, gJavaActivityClass);
}
