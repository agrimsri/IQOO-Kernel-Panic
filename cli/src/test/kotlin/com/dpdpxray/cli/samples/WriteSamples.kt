package com.dpdpxray.cli.samples

import java.io.File

/** Entry point of `./gradlew :cli:writeSamples`: writes the synthetic sample bundles into docs/samples. */
fun main(args: Array<String>) {
    val dir = File(args.firstOrNull() ?: error("usage: WriteSamples <docs/samples dir>"))
    SampleBundles.write(dir)
    SampleBundles.files().keys.forEach { println("wrote ${File(dir, it).path}") }
}
