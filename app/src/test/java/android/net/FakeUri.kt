package android.net

import android.os.Parcel

/**
 * 测试专用 Uri 占位：本地单测的 stub android.jar 无法构造真实 Uri 实例，
 * 而 Uri 的构造器是包私有、无法在应用包内继承，故放在同包下。
 * 仅 toString() 参与被测逻辑（身份比较），其余成员返回默认值。
 */
class FakeUri(private val s: String) : Uri() {
    override fun toString(): String = s
    override fun equals(other: Any?): Boolean = other is FakeUri && other.s == s
    override fun hashCode(): Int = s.hashCode()
    override fun compareTo(other: Uri): Int = 0
    override fun isHierarchical(): Boolean = false
    override fun isRelative(): Boolean = true
    override fun buildUpon(): Builder = throw UnsupportedOperationException()
    override fun getScheme(): String? = null
    override fun getSchemeSpecificPart(): String? = null
    override fun getEncodedSchemeSpecificPart(): String? = null
    override fun getAuthority(): String? = null
    override fun getEncodedAuthority(): String? = null
    override fun getUserInfo(): String? = null
    override fun getEncodedUserInfo(): String? = null
    override fun getHost(): String? = null
    override fun getPort(): Int = -1
    override fun getPath(): String? = null
    override fun getEncodedPath(): String? = null
    override fun getQuery(): String? = null
    override fun getEncodedQuery(): String? = null
    override fun getFragment(): String? = null
    override fun getEncodedFragment(): String? = null
    override fun getPathSegments(): List<String> = emptyList()
    override fun getLastPathSegment(): String? = null
    override fun describeContents(): Int = 0
    override fun writeToParcel(dest: Parcel, flags: Int) = throw UnsupportedOperationException()
}
