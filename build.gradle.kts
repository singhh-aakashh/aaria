plugins {
    id("com.android.application") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}

// Load API keys from .env (copy from sample.env and fill in values)
subprojects {
    val envFile = rootProject.file(".env")
    if (envFile.exists()) {
        envFile.readLines().forEach { line ->
            val t = line.trim()
            if (t.isNotEmpty() && !t.startsWith("#")) {
                val i = t.indexOf('=')
                if (i > 0) {
                    val k = t.substring(0, i).trim()
                    var v = t.substring(i + 1).trim()
                    if (v.startsWith("\"") && v.endsWith("\"")) v = v.drop(1).dropLast(1)
                    project.ext.set(k, v)
                }
            }
        }
    }
}
