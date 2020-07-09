package docker.build

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.model.AuthConfig
import com.github.dockerjava.api.model.BuildResponseItem
import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.core.DockerClientBuilder
import com.github.dockerjava.core.command.BuildImageResultCallback
import com.github.dockerjava.core.command.PushImageResultCallback
import com.github.dockerjava.okhttp.OkHttpDockerCmdExecFactory
import org.apache.tools.ant.taskdefs.condition.Os
import org.gradle.api.Project

class DockerClientProvider {

    static DockerClient getDockerClient() {
        if (Os.isFamily(Os.FAMILY_WINDOWS)) {
            return DockerClientBuilder.getInstance(DefaultDockerClientConfig.createDefaultConfigBuilder()
                    .withDockerHost("npipe:////./pipe/docker_engine").build()).withDockerCmdExecFactory(new OkHttpDockerCmdExecFactory()).build()
        } else {
            return DockerClientBuilder.getInstance().build()
        }
    }

    static void buildDockerImage(File dockerDir,
                                 String imageName,
                                 String imageVersion,
                                 Boolean shouldPush,
                                 Project project
    ) {
        def resourceDir = dockerDir
        DockerClient dockerClient = dockerClient
        BuildImageResultCallback callback = new BuildImageResultCallback() {
            @Override
            void onNext(BuildResponseItem item) {
                if (item.stream != null) {
                    System.out.println("" + item.getStream().trim());
                }
                super.onNext(item);
            }
        }
        def imageId = dockerClient.buildImageCmd(resourceDir).exec(callback).awaitImageId();
        dockerClient.tagImageCmd(imageId, imageName, imageVersion).exec()
        println "Successfully built and tagged $imageId as ${imageName}:${imageVersion}"

        if (shouldPush) {
            def pushResult = dockerClient.pushImageCmd("$imageName:$imageVersion")
                    .withAuthConfig(new AuthConfig()
                            .withUsername(project.property("docker.push.username").toString())
                            .withPassword(project.property("docker.push.password").toString())).exec(new PushImageResultCallback()).awaitCompletion()
            println "Successfully pushed ${imageName}:${imageVersion}"
        }
    }

}
