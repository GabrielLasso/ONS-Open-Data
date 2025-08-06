package br.com.libraenergia.onsopendata

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform