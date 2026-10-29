# JNI resolves entry points by class/method name and calls createBitmap by name.
-keep class com.letter.alphapro.core.heif.HeifDecoder {
    native <methods>;
    public static android.graphics.Bitmap createBitmap(int, int);
}
