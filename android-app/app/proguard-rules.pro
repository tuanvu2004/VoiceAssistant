# sherpa-onnx dùng JNI với tên lớp/phương thức cố định (được gọi từ mã C++ theo tên),
# nên phải giữ nguyên không cho ProGuard/R8 đổi tên khi build bản release.
-keep class com.k2fsa.sherpa.onnx.** { *; }
