/* EPUB writer adapted from ReadEra's EraMobiConvert.cpp (AGPL-3.0-or-later).
 * libmobi is LGPL-3.0-or-later; miniz is public domain.
 */
#include <jni.h>
#include <cstdio>
#include <cstring>
#include <string>
#include <unistd.h>
#include "mobi.h"
#define MINIZ_HEADER_FILE_ONLY
#define MINIZ_NO_ZLIB_COMPATIBLE_NAMES
#include "miniz.c"
#undef MINIZ_HEADER_FILE_ONLY
#undef MINIZ_NO_ZLIB_COMPATIBLE_NAMES
static constexpr const char* kContainer = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\"><rootfiles><rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>";
static constexpr const char* kMime = "application/epub+zip";

static bool add_parts(mz_zip_archive* zip, const MOBIPart* parts, const char* prefix) {
    char path[256];
    for (auto* part = parts; part; part = part->next) {
        MOBIFileMeta meta = mobi_get_filemeta_by_type(part->type);
        int n = snprintf(path, sizeof(path), "%s%05zu.%s", prefix, part->uid, meta.extension);
        if (n < 0 || static_cast<size_t>(n) >= sizeof(path) ||
            !mz_zip_writer_add_mem(zip, path, part->data, part->size, MZ_DEFAULT_COMPRESSION)) return false;
    }
    return true;
}

static bool write_epub(const MOBIRawml* rawml, const char* target) {
    if (!rawml || !target || !*target) return false;
    mz_zip_archive zip{};
    if (!mz_zip_writer_init_file(&zip, target, 0)) return false;
    bool ok = mz_zip_writer_add_mem(&zip, "mimetype", kMime, sizeof(kMime) - 1, MZ_NO_COMPRESSION) &&
              mz_zip_writer_add_mem(&zip, "META-INF/container.xml", kContainer, sizeof(kContainer) - 1, MZ_DEFAULT_COMPRESSION) &&
              add_parts(&zip, rawml->markup, "OEBPS/part");
    if (ok && rawml->flow) ok = add_parts(&zip, rawml->flow->next, "OEBPS/flow");
    char path[256];
    for (auto* part = rawml->resources; ok && part; part = part->next) {
        if (part->size == 0) continue;
        MOBIFileMeta meta = mobi_get_filemeta_by_type(part->type);
        int n = meta.type == T_OPF
                ? snprintf(path, sizeof(path), "OEBPS/content.opf")
                : snprintf(path, sizeof(path), "OEBPS/resource%05zu.%s", part->uid, meta.extension);
        ok = n >= 0 && static_cast<size_t>(n) < sizeof(path) &&
             mz_zip_writer_add_mem(&zip, path, part->data, part->size, MZ_DEFAULT_COMPRESSION);
    }
    if (ok) ok = mz_zip_writer_finalize_archive(&zip) != 0;
    bool ended = mz_zip_writer_end(&zip) != 0;
    return ok && ended;
}

static bool convert_mobi(const char* source, const char* target) {
    if (!source || !*source || !target || !*target) return false;
    unlink(target);
    MOBIData* mobi = mobi_init();
    if (!mobi) return false;
    bool success = false;
    MOBIRawml* rawml = nullptr;
    const MOBIData* part = nullptr;
    if (mobi_load_filename(mobi, source) != MOBI_SUCCESS) goto cleanup;
    part = mobi;
    do {
        const MOBIPdbRecord* record0 = mobi_get_record_by_seqnumber(part, 0);
        // Record 0 stores encryption_type at byte 12; check raw bytes as well for non-Mobipocket PDBs.
        const unsigned recordEncryption =
            record0 && record0->data && record0->size >= 14
                ? (static_cast<unsigned>(record0->data[12]) << 8) | record0->data[13]
                : 0;
        if (mobi_is_encrypted(part) ||
            (part->rh && part->rh->encryption_type != 0) ||
            recordEncryption != 0) {
            goto cleanup;
        }
        part = part->next;
    } while (part && part != mobi);
    rawml = mobi_init_rawml(mobi);
    if (!rawml || mobi_parse_rawml(rawml, mobi) != MOBI_SUCCESS) goto cleanup;
    success = write_epub(rawml, target);
cleanup:
    if (rawml) mobi_free_rawml(rawml);
    mobi_free(mobi);
    if (!success) unlink(target);
    return success;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_readera_openreadera_engine_MobiConverter_nativeConvertMobiToEpub(
        JNIEnv* env, jclass, jstring sourcePath, jstring targetPath) {
    if (!sourcePath || !targetPath) return JNI_FALSE;
    const char* source = env->GetStringUTFChars(sourcePath, nullptr);
    if (!source) return JNI_FALSE;
    const char* target = env->GetStringUTFChars(targetPath, nullptr);
    if (!target) {
        env->ReleaseStringUTFChars(sourcePath, source);
        return JNI_FALSE;
    }
    bool converted = convert_mobi(source, target);
    env->ReleaseStringUTFChars(targetPath, target);
    env->ReleaseStringUTFChars(sourcePath, source);
    return converted ? JNI_TRUE : JNI_FALSE;
}
