package hudson.plugins.gradle.model;


import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ModelBuilder;
import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.model.ExternalDependency;
import org.gradle.tooling.model.eclipse.EclipseProject;
import org.gradle.tooling.model.gradle.GradlePublication;
import org.gradle.tooling.model.gradle.ProjectPublications;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class GradleDependencyInfoFactory {
    public GradleDependencyInfo newInstance(File gradleFile) {
        GradleConnector connector = GradleConnector.newConnector();
        connector.forProjectDirectory(gradleFile.getParentFile());

        ProjectConnection connection = null;

        try {
            connection = connector.connect();
            ModelBuilder<EclipseProject> customModelBuilder = connection.model(EclipseProject.class);
            setModelArguments(gradleFile, customModelBuilder);

            ModelBuilder<ProjectPublications> projectPublicationsModelBuilder = connection.model(ProjectPublications.class);
            setModelArguments(gradleFile, projectPublicationsModelBuilder);

            ProjectPublications publications = projectPublicationsModelBuilder.get();
            EclipseProject eclipseProject = customModelBuilder.get();

            GradleDependencyInfo gradleDependencyInfo = createGradleDependencyInfo(eclipseProject, publications, projectPublicationsModelBuilder);

            return gradleDependencyInfo;

        } finally {
            if (connection != null) {
                connection.close();
            }
        }
    }

    private void setModelArguments(File gradleFile, ModelBuilder<?> customModelBuilder) {

        if (!gradleFile.exists())
            return;

        customModelBuilder.withArguments("-b", gradleFile.getAbsolutePath());
    }

    private GradleDependencyInfo createGradleDependencyInfo(EclipseProject eclipseProject, ProjectPublications publications, ModelBuilder<ProjectPublications> projectPublicationsModelBuilder) {

        GradleDependencyInfo gradleDependencyInfo = new GradleDependencyInfo();

        gradleDependencyInfo.setName(eclipseProject.getGradleProject().getName());

        List<Dependency> publishedDependencies = new ArrayList<Dependency>();

        for (GradlePublication publication : publications.getPublications().getAll()) {
            Dependency publishedDependency = new Dependency();
            publishedDependency.setGroup(publication.getId().getGroup());
            publishedDependency.setName(publication.getId().getName());
            publishedDependencies.add(publishedDependency);
        }

        gradleDependencyInfo.setPublications(publishedDependencies);
        gradleDependencyInfo.setMultiProject(!eclipseProject.getChildren().isEmpty());

        gradleDependencyInfo.setGradleFile(eclipseProject.getGradleProject().getBuildScript().getSourceFile());

        List<Dependency> dependencies = new ArrayList<Dependency>();

        if (eclipseProject.getClasspath() != null) {
            for (ExternalDependency dep : eclipseProject.getClasspath().getAll()) {
                Dependency dependency = new Dependency();
                dependency.setName(dep.getGradleModuleVersion().getName());
                dependency.setGroup(dep.getGradleModuleVersion().getGroup());
                dependencies.add(dependency);
            }
        }

        gradleDependencyInfo.setDependencies(dependencies);

        List<GradleDependencyInfo> children = new ArrayList<GradleDependencyInfo>();

        for (EclipseProject childProject : eclipseProject.getChildren()) {
            GradleDependencyInfo childDependencyInfo = null;

            if (hasChildBuildScript(childProject)) {
                setModelArguments(childProject.getGradleProject().getBuildScript().getSourceFile(), projectPublicationsModelBuilder);
                childDependencyInfo = createGradleDependencyInfo(childProject, projectPublicationsModelBuilder.get(), projectPublicationsModelBuilder);
                setModelArguments(gradleDependencyInfo.getGradleFile(), projectPublicationsModelBuilder);
            }
            else {
                // children without a build script file need a new connection to the project directory ??
                // even though this file doesn't exist, the build arguments will not be set
                childDependencyInfo = newInstance(childProject.getGradleProject().getBuildScript().getSourceFile());
            }

            children.add(childDependencyInfo);
        }

        gradleDependencyInfo.setChildren(children);

        return gradleDependencyInfo;
    }

    private boolean hasChildBuildScript(EclipseProject childProject) {

        if (childProject.getGradleProject().getBuildScript().getSourceFile() != null && childProject.getGradleProject().getBuildScript().getSourceFile().exists())
            return true;

        return false;
    }
}
