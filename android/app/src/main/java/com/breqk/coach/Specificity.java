package com.Break.coach;

/**
 * Specificity
 * -----------
 * Classification of how concrete the user's typed intent is.
 *
 *   SPECIFIC — names a real video, creator, topic, or task
 *              (e.g. "lecture 4 of cs229 backprop"). Leans toward APPROVE.
 *   VAGUE    — generic filler with no topic (e.g. "youtube", "just browsing").
 *              Per spec, vague intent always lands at PROBE or CHALLENGE.
 *
 * Produced by {@link IntentClassifier}; consumed by {@link VerdictEngine}.
 *
 * Logging: none — pure value enum.
 */
public enum Specificity {
    SPECIFIC,
    VAGUE
}
