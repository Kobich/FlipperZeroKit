package dev.flipper.internal.rpc

internal object RemoteNames {
    fun candidate(path: String, index: Int): String {
        require(index >= 1)
        if (index == 1) return path
        val parent = path.substringBeforeLast('/', "")
        val name = path.substringAfterLast('/')
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        return parent + "/" + name.take(dot) + " (" + index + ")" + name.substring(dot)
    }
}
