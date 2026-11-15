# Commons Compress registers ZIP extra-field implementations with Class.newInstance().
# yt-dlp unpacks its Python/FFmpeg archives through this code on first import.
# Without these constructors R8 turns the classes into non-instantiable types,
# and ExtraFieldUtils.<clinit> throws "class ... is not a concrete class".
-keep,allowobfuscation class * implements org.apache.commons.compress.archivers.zip.ZipExtraField {
    public <init>();
}
