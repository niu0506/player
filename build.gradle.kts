// 根构建脚本：仅声明插件版本，具体应用在 app 模块。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.ksp) apply false
}
