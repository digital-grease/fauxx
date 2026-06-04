package com.fauxx.support

import kotlin.random.Random

/**
 * Fixed-seed [Random] for deterministic, reproducible property/statistical tests. Pass the
 * result into a seam-injected consumer (e.g. `PoissonScheduler(clock, seededRandom())`) so a
 * failing case is reproducible from the seed rather than flaky across runs.
 */
fun seededRandom(seed: Long = 20260601L): Random = Random(seed)
