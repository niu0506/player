plugins {
    id("com.android.application") version "9.4.0" apply false
    // KSP 2.3.0 起与 Kotlin 编译器版本解耦；AGP 9 内建 Kotlin 需 2.3.1+（Room 注解处理用）
    id("com.google.devtools.ksp") version "2.3.11" apply false
}
