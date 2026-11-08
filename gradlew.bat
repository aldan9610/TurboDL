@echo off
java -cp "%~dp0gradle\wrapper\gradle-wrapper.jar;%~dp0gradle\wrapper\gradle-wrapper-shared.jar" org.gradle.wrapper.GradleWrapperMain %*
