# 本地开发环境（box 上复用已安装的 JDK 17 与 Android SDK；按需修改路径）
export JAVA_HOME=${JAVA_HOME:-/workspace/wherepageslead-android/tools/jdk-17.0.20.1+1}
export ANDROID_HOME=${ANDROID_HOME:-/workspace/wherepageslead-android/tools/sdk}
export PATH=$JAVA_HOME/bin:$PATH
