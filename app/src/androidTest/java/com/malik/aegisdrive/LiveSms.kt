package com.malik.aegisdrive

/**
 * Marks an instrumented test that performs a **real, irreversible, user-visible side effect** —
 * currently: sending an actual SMS from the handset to the saved emergency contact.
 *
 * Tests carrying this annotation are excluded from every Gradle-driven run
 * (`connectedAndroidTest`, and therefore Android Studio's "run all tests") by the
 * `notAnnotation` filter configured in `app/build.gradle.kts`. Without that filter a
 * teammate running the normal test task would silently message a real person.
 *
 * To run one deliberately, invoke it by class name through adb, which bypasses Gradle
 * and its filter:
 *
 * ```
 * adb shell am instrument -w -e class com.malik.aegisdrive.LiveEmergencySmsTest \
 *     com.malik.aegisdrive.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class LiveSms
