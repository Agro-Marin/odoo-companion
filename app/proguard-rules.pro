# R8 rules for the release build.
#
# Referenced by build.gradle.kts and previously absent, which R8 reports as
# "Supplied proguard configuration does not exist" and otherwise tolerates. It
# was in fact harmless -- checked against the mapping, R8 keeps
# `CallLogUpload$$serializer` and every `@SerialName` string in the minified
# dex, because kotlinx-serialization, Room and WorkManager all ship consumer
# rules in their artifacts. No app-owned keep rule is needed for any of that,
# and adding one would only shadow a real regression in theirs.
#
# What is worth asking for is below.

# A fleet handset is not attached to a debugger: a stack trace out of a bug
# report is the whole diagnosis, and without these it names no file and no line.
# The source file is renamed rather than kept, so it leaks nothing while the
# line numbers stay resolvable through mapping.txt.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# OkHttp and Okio publish these for their own optional dependencies; without
# them the build log carries warnings that hide anything real that appears.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
