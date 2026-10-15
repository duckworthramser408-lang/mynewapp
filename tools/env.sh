# Source from a Bash shell. Keep user HOME unchanged.
export G23_TOOLCHAINS="${G23_TOOLCHAINS:-/workspace/toolchains}"
export JAVA_HOME="$G23_TOOLCHAINS/jdk-17.0.20.1+1"
export ANDROID_HOME="$G23_TOOLCHAINS/android-sdk"
export ANDROID_USER_HOME="$G23_TOOLCHAINS/android-home"
export GRADLE_USER_HOME="$G23_TOOLCHAINS/gradle-home"
export G23_GRADLE="$G23_TOOLCHAINS/gradle-8.9/bin/gradle"
export PATH="$G23_TOOLCHAINS/g23-python/bin:$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmake/3.22.1/bin:$PATH"
if [ -r /etc/ssl/certs/java/cacerts ]; then
 export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts"
fi
