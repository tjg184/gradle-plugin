package hudson.plugins.gradle.model;

import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class GradleDependencyInfo implements Serializable {
    private static long serialVersionUID = 1113799434508676095L;

    @Override
    public String toString() {
        return "GradleDependencyInfo{" +
                "gradleFile=" + gradleFile +
                ", name='" + name + '\'' +
                ", isMultiProject=" + isMultiProject +
                ", children=" + children +
                ", dependencies=" + dependencies +
                ", publications=" + publications +
                '}';
    }

    public static interface DependencyFilter {
        boolean accept(Dependency dependency);
    }

    public static class DefaultDependencyFilter implements DependencyFilter {

        private final String name;
        private final String group;

        public DefaultDependencyFilter(String group, String name) {
            this.group = group;
            this.name = name;
        }

        public boolean accept(Dependency dependency) {
            return group.equalsIgnoreCase(dependency.getGroup()) && name.equalsIgnoreCase(dependency.getName());
        }
    }

    private File gradleFile;
    private String name;
    private boolean isMultiProject;
    private List<GradleDependencyInfo> children;
    private List<Dependency> dependencies;
    private List<Dependency> publications;

    public void setGradleFile(File gradleFile) {
        this.gradleFile = gradleFile;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setMultiProject(boolean isMultiProject) {
        this.isMultiProject = isMultiProject;
    }

    public void setChildren(List<GradleDependencyInfo> children) {
        this.children = children;
    }

    public void setDependencies(List<Dependency> dependencies) {
        this.dependencies = dependencies;
    }

    public GradleDependencyInfo() {
    }

    public String getName() {
        return name;
    }

    public File getGradleFile() { return gradleFile; }

    public boolean isMultiProject() {
        return isMultiProject;
    }

    public List<GradleDependencyInfo> getChildGradleDependencyInfos() {
        return children;
    }

    public void setPublications(List<Dependency> publications) {
        this.publications = publications;
    }

    public List<Dependency> getPublications() {
        return publications;
    }

    /**
     * Compares dependencies only between this and another dependency info
     * @param other
     * @return true if the dependencies match
     */
    public boolean compareAllDependencies(GradleDependencyInfo other) {

        if (other == null)
            return false;

        if (this.getAllDependencies().size() != other.getAllDependencies().size())
            return false;

        for (Dependency thisDep : getAllDependencies()) {
            List<Dependency> otherDeps = other.getAllDependencies(thisDep);

            if (otherDeps == null || otherDeps.size() == 0)
                return false;
        }

        return true;
    }

    public List<Dependency> getAllDependencies(Dependency dep) {
        List<Dependency> deps = getAllDependencies(new DefaultDependencyFilter(dep.getGroup(), dep.getName()));
        return deps;
    }

    public List<Dependency> getAllDependencies(DependencyFilter filter) {
        List<Dependency> match = new ArrayList<Dependency>();

        for (Dependency dep : getAllDependencies()) {
            if (filter.accept(dep)) {
                match.add(dep);
            }
        }

        return match;
    }

    public List<Dependency> getAllDependencies() {
        List<Dependency> all = new ArrayList<Dependency>();
        all.addAll(getDependencies());

        for (GradleDependencyInfo child : getChildGradleDependencyInfos()) {
            all.addAll(child.getAllDependencies());
        }

        return all;
    }

    public List<Dependency> getDependencies() {
        return dependencies;
    }
}
