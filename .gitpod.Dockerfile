FROM gitpod/workspace-java-17

RUN bash -c ". /home/gitpod/.sdkman/bin/sdkman-init.sh && sdk install sbt"