package hudson.plugins.gradle;

import hudson.*;
import hudson.model.*;
import hudson.plugins.gradle.model.Dependency;
import hudson.plugins.gradle.model.DependencyStore;
import hudson.plugins.gradle.model.GradleDependencyInfo;
import hudson.plugins.gradle.model.GradleDependencyInfoFactory;
import hudson.tasks.*;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.*;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class GradleBuildTrigger extends Notifier implements DependecyDeclarer {

    public static final String GRADLE_FILE = "build.gradle";
    public static final String GRADLE_CACHE = "gradle-dependencies.ser";
    private String tagName;
    private String templateProject;
    private static final Logger LOGGER = Logger.getLogger(GradleBuildTrigger.class.getName());
    private transient List<GradleDependencyInfo> gradleDependencyInfo;
    private DependencyStore dependencyStore;
    private GradleDependencyInfoFactory factory;

    @DataBoundConstructor
    public GradleBuildTrigger(String tagName, String templateProject) {
        this.tagName = tagName == null ? tagName : tagName.trim();
        this.templateProject = templateProject == null ? templateProject : templateProject.trim();
    }

    public String getTagName() {
        return tagName;
    }

    @SuppressWarnings("unused")
    public String getTemplateProject() {
        return templateProject;
    }

    @Override
    public boolean perform(AbstractBuild<?,?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
        LOGGER.log(Level.INFO, "Building " + build.getFullDisplayName());
        rebuildGradleDependencyInfo(build);
        LOGGER.log(Level.INFO, "Finished building " + build.getFullDisplayName());
        return true;
    }

    private void rebuildGradleDependencyInfo(AbstractBuild<?,?> b) {

        if (b == null)
            return;

        boolean rebuild = rebuildDependencies(b);
        LOGGER.log(Level.INFO, "Rebuilding (" + rebuild + ") " + b.getFullDisplayName());

        if (rebuild)
            triggerRebuild();
    }

    static void triggerRebuild() {
        DESCRIPTOR.invalidateProjectMap();
        Hudson.getInstance().rebuildDependencyGraph();
    }

    private boolean rebuildDependencies(AbstractBuild<?, ?> b) {
        if (gradleDependencyInfo == null) {
            setGradleDependencyInfo(b.getProject(), rebuildGradleDependencyInfo(b.getProject()));
            return true;
        }

        List<GradleDependencyInfo> originalDependencyInfo = getGradleDependencyInfo(b.getProject());
        List<GradleDependencyInfo> newDependencyInfo = rebuildGradleDependencyInfo(b.getProject());

        boolean rebuild = false;

        LOGGER.log(Level.INFO, "Comparing " + originalDependencyInfo + " " + newDependencyInfo);

        if (originalDependencyInfo.size() != newDependencyInfo.size())
            rebuild = true;

        for (GradleDependencyInfo original : originalDependencyInfo) {
            boolean nameFound = false;

            for (GradleDependencyInfo newDependency : newDependencyInfo) {
                if (original.getName().equalsIgnoreCase(newDependency.getName())) { //TODO: how do we accurately compare these??

                    nameFound = true;

                    if (!original.compareAllDependencies(newDependency)) {
                        rebuild = true;
                        break;
                    }

                    if (!original.getGradleFile().equals(newDependency.getGradleFile())) {
                        rebuild = true;
                        break;
                    }
                }
            }

            if (nameFound == false)
                rebuild = true;
        }

        if (rebuild) {
            setGradleDependencyInfo(b.getProject(), newDependencyInfo);
            return true;
        }

        return false;
    }

    void setGradleDependencyInfo(AbstractProject project, List<GradleDependencyInfo> newDependencyInfo) {

        // no need to set if we couldn't find anything
        if (newDependencyInfo == null || newDependencyInfo.size() == 0)
            return;

        gradleDependencyInfo = newDependencyInfo;
        storeDependencyInfo(gradleDependencyInfo, project);
    }

    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.BUILD;
    }

    public void buildDependencyGraph(AbstractProject abstractProject, DependencyGraph dependencyGraph) {
        if (abstractProject instanceof Project) {
            List<GradleDependencyInfo> gradleDependencyInfos = getOrRebuildGradleDependencyInfo(abstractProject);

            if (gradleDependencyInfos.size() > 0)
                LOGGER.log(Level.INFO, "Building graph for " + abstractProject.getName());

            for (GradleDependencyInfo gradleDependencyInfo : gradleDependencyInfos) {
                addChildDependencies(abstractProject, dependencyGraph, gradleDependencyInfo);

                if (gradleDependencyInfo.isMultiProject()) {
                    for (GradleDependencyInfo child : gradleDependencyInfo.getChildGradleDependencyInfos()) {
                        addChildDependencies(abstractProject, dependencyGraph, child);
                    }
                }
            }
        }
    }

    private void addChildDependencies(AbstractProject abstractProject, DependencyGraph dependencyGraph, GradleDependencyInfo gradleDependencyInfo) {
        for (Dependency dep : gradleDependencyInfo.getDependencies()) {

            List<AbstractProject> possibleDeps = DESCRIPTOR.getProjects(dep);

            if (possibleDeps.isEmpty())
                LOGGER.log(Level.FINE, "Dependency list is empty");

            for (AbstractProject possibleProject : possibleDeps) {

                if (possibleProject.isDisabled()) continue; // no point in enabling this

                LOGGER.log(Level.FINE, "Adding dependency " + possibleProject.getName());

                if (isMatching(possibleProject, abstractProject)) {
                    if (!possibleProject.equals(abstractProject)) { // prevent circular reference
                        dependencyGraph.addDependency(new GradleModuleDependency(possibleProject, abstractProject));
                    }
                }
            }
        }
    }

    private boolean isMatching(AbstractProject other, AbstractProject owner) {

        if (other instanceof Project && owner instanceof Project) {
            GradleBuildTrigger otherTrigger = getGradleBuildTrigger(other);
            GradleBuildTrigger ownerTrigger = getGradleBuildTrigger(owner);

            if (ownerTrigger.getTagName() == null || otherTrigger.getTagName() == null) {
                // assume they're in the global group which always needs notified
                return true;
            }

            if (otherTrigger.getTagName().equalsIgnoreCase(ownerTrigger.getTagName())) {
                return true;
            }
        }

        return false;
    }

    static GradleBuildTrigger getGradleBuildTrigger(AbstractProject other) {

        if (other instanceof Project) {
            Project p = (Project) other;
            return (GradleBuildTrigger) p.getPublisher(DESCRIPTOR);
        }

        return null;
    }

    private List<GradleDependencyInfo> getOrRebuildGradleDependencyInfo(AbstractProject abstractProject) {
        List<GradleDependencyInfo> gradleDependencyInfos = getGradleDependencyInfo(abstractProject);

        if (gradleDependencyInfos == null || gradleDependencyInfos.size() == 0) {
            gradleDependencyInfos = rebuildGradleDependencyInfo(abstractProject);
            setGradleDependencyInfo(abstractProject, gradleDependencyInfos);
        }

        if (gradleDependencyInfos == null || gradleDependencyInfos.size() == 0) {
            gradleDependencyInfos = cloneFromTemplate();
            setGradleDependencyInfo(abstractProject, gradleDependencyInfos);
        }

        if (gradleDependencyInfos == null)
            return Collections.emptyList();

        return gradleDependencyInfos;
    }

    private List<GradleDependencyInfo> getGradleDependencyInfo(AbstractProject abstractProject) {
        if (gradleDependencyInfo == null) {
            gradleDependencyInfo = readDependencyInfoFromStore(abstractProject);
        }

        return gradleDependencyInfo;
    }

    private List<GradleDependencyInfo> cloneFromTemplate() {

        if (templateProject == null)
            return null;

        Project other = getProjectByName(templateProject);
        GradleBuildTrigger otherTrigger = getGradleBuildTrigger(other);

        if (otherTrigger != null) {
            LOGGER.log(Level.INFO, "Cloning dependencies from " + other.getName());
            return otherTrigger.getOrRebuildGradleDependencyInfo(other);
        }

        return null;
    }

    private Project getProjectByName(String copyFromName) {
        List<Project> projects = Hudson.getInstance().getAllItems(Project.class);

        for (Project other : projects) {
            if (other.getName().equalsIgnoreCase(copyFromName)) {
                return other;
            }
        }

        return null;
    }

    private List<GradleDependencyInfo> rebuildGradleDependencyInfo(AbstractProject abstractProject) {
        List<GradleDependencyInfo> gradleDependencyInfo = new ArrayList<GradleDependencyInfo>();

        if (abstractProject instanceof Project) {
            Project p = (Project) abstractProject;

            for (Object b : p.getBuilders()) {
                Builder builder = (Builder) b;

                if (builder instanceof Gradle) {
                    Gradle gradle = (Gradle) builder;

                    File buildFile = getBuildFile(gradle, abstractProject);

                    if (buildFile != null) {
                        gradleDependencyInfo.add(factory().newInstance(buildFile));
                    }
                }
            }
        }

        return gradleDependencyInfo;
    }

    private GradleDependencyInfoFactory factory() {
        if (factory == null) {
            factory = new GradleDependencyInfoFactory();
        }

        return factory;
    }

    private DependencyStore getDependencyStore() {
        if (dependencyStore == null) {
            dependencyStore = new DependencyStore();
        }

        return dependencyStore;
    }

    private List<GradleDependencyInfo> readDependencyInfoFromStore(AbstractProject project) {
        return getDependencyStore().load(new File(project.getRootDir(), GRADLE_CACHE));
    }

    private void storeDependencyInfo(List<GradleDependencyInfo> gradleDependencyInfo, AbstractProject project) {
        getDependencyStore().store(new File(project.getRootDir(), GRADLE_CACHE), gradleDependencyInfo);
    }

    private File getBuildFile(Gradle gradle, AbstractProject project) {

        File file = null;

        String buildFileNormalized = gradle.getBuildFile();

        EnvVars env = null;

        try {
            if (project.getLastBuild() != null)
                env = project.getLastBuild().getEnvironment(TaskListener.NULL);
        } catch (IOException e) {
        } catch (InterruptedException e) {
        }

        // user specified a Gradle build file in the Gradle configuration
        if (buildFileNormalized != null && buildFileNormalized.trim().length() > 0) {
            if (env != null)
                buildFileNormalized = Util.replaceMacro(gradle.getBuildFile(), env);

            file = new File(buildFileNormalized);

            LOGGER.log(Level.FINE, "Custom Gradle script file " + file);

            if (file.exists())
                return file;
        }

        // root build script directory specified
        String rootBuildScriptDir = gradle.getRootBuildScriptDir();
        if (rootBuildScriptDir != null && rootBuildScriptDir.trim().length() > 0) {
            AbstractBuild build = (AbstractBuild) project.getLastBuild();
            String rootBuildScriptNormalized = rootBuildScriptDir.trim().replaceAll("[\t\r\n]+", " ");
            rootBuildScriptNormalized = Util.replaceMacro(rootBuildScriptNormalized.trim(), env);
            rootBuildScriptNormalized = Util.replaceMacro(rootBuildScriptNormalized, build.getBuildVariableResolver());
            FilePath normalizedRootBuildScriptDir = new FilePath(build.getModuleRoot(), rootBuildScriptNormalized);

            LOGGER.log(Level.FINE, "Root build script directory " + normalizedRootBuildScriptDir);
            file = new File(normalizedRootBuildScriptDir.getRemote(), GRADLE_FILE);

            if (file.exists())
                return file;
        }

        // use Gradle file in the workspace directory
        try {
            if (project.getSomeWorkspace() != null)
                file = new File(project.getSomeWorkspace().child(GRADLE_FILE).toURI());
        } catch (Exception ignore) {
        }

        if (file != null && file.exists())
            return file;

        // use Gradle file in the project directory
        file = new File(project.getRootDir(), GRADLE_FILE);

        if (file.exists())
            return file;

        LOGGER.log(Level.WARNING, "Could not find a Gradle file for " + project.getName());
        return null;
    }

    @Extension
    public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();

    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        private Map<Dependency, List<AbstractProject>> nameToProjectMap;

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return Project.class.isAssignableFrom(jobType);
        }

        @Override
        public String getDisplayName() {
            return "Gradle Build Trigger";
        }

        private synchronized List<AbstractProject> getProjects(Dependency dependency) {
            if (nameToProjectMap == null) {
                calculateProjectMap();
            }

            List<AbstractProject> result = nameToProjectMap.get(dependency);
            if (result == null) result = Collections.emptyList();
            return result;
        }

        private void calculateProjectMap() {
            List<Project> projects = Hudson.getInstance().getAllItems(Project.class);
            Map<Dependency, List<AbstractProject>> projectMap = new HashMap<Dependency, List<AbstractProject>>();
            for (Project p : projects) {
                if (p.isDisabled()) {
                    continue;
                }
                GradleBuildTrigger gradleBuildTrigger = (GradleBuildTrigger) p.getPublisher(DESCRIPTOR);
                if (gradleBuildTrigger != null) {

                    LOGGER.log(Level.FINE, "Using build trigger to calculate dependencies for " + p.getName());

                    List<GradleDependencyInfo> dependencyInfoList = null;

                    try {
                        dependencyInfoList = gradleBuildTrigger.getOrRebuildGradleDependencyInfo(p);
                    } catch (Exception e) {
                        LOGGER.log(Level.SEVERE, "Failed to get dependency info", e);
                        continue;
                    }

                    if (dependencyInfoList.size() > 0)
                        LOGGER.log(Level.FINE, "Built dependencies for " + p.getName() + " " + dependencyInfoList);

                    for (GradleDependencyInfo dependencyInfo : dependencyInfoList) {
                        if (dependencyInfo.isMultiProject()) { // point child projects to parent Jenkins name
                            for (GradleDependencyInfo child : dependencyInfo.getChildGradleDependencyInfos()) {
                                addProjectMapping(projectMap, p, child);
                            }
                        }
                        addProjectMapping(projectMap, p, dependencyInfo);
                    }
                }
            }

            LOGGER.log(Level.FINE, "Built project map " + projectMap);

            this.nameToProjectMap = projectMap;
        }

        private void addProjectMapping(Map<Dependency, List<AbstractProject>> projectMap, Project targetProject, GradleDependencyInfo dependencyInfo) {
            for (Dependency publishedDependency : dependencyInfo.getPublications()) {

                List<AbstractProject> list = projectMap.get(publishedDependency);

                if (list == null) {
                    list = new ArrayList<AbstractProject>();
                }

                list.add(targetProject);
                LOGGER.log(Level.FINE, "Adding name " + publishedDependency + " for project " + targetProject.getName());
                projectMap.put(publishedDependency, list);
            }
        }

        public synchronized void invalidateProjectMap() {
            this.nameToProjectMap = null;
        }
    }

    protected static class GradleModuleDependency extends DependencyGraph.Dependency {

        public GradleModuleDependency(AbstractProject upstream, AbstractProject downstream) {
            super(upstream, downstream);
        }

        public boolean shouldTriggerBuild(AbstractBuild build, TaskListener listener, List<Action> actions) {
            if (build.getResult().isWorseThan(Result.SUCCESS)) {
                return false;
            }
            else {
                AbstractProject downstreamProject = getDownstreamProject();
                LOGGER.log(Level.INFO, "Considering whether to trigger " + downstreamProject + " or not");

                AbstractProject parent = getUpstreamProject();
                if (areUpstreamsBuilding(downstreamProject, parent)) {
                    LOGGER.log(Level.INFO, " -> No, because downstream has dependencies already building or in queue");
                    return false;
                }
                else if (inDownstreamProjects(downstreamProject)) {
                    LOGGER.log(Level.INFO, " -> No, because downstream has dependencies in the downstream projects list");
                    return false;
                }
                else {
                    AbstractBuild dlb = (AbstractBuild) downstreamProject.getLastBuild();

                    for (Object obj : downstreamProject.getUpstreamProjects()) {
                        AbstractProject up = (AbstractProject) obj;
                        Object ulb;
                        if (up == parent) {
                            if (build.getResult() != null && build.getResult().isWorseThan(Result.UNSTABLE)) {
                                ulb = up.getLastSuccessfulBuild();
                            } else {
                                ulb = build;
                            }
                        }
                        else {
                            ulb = up.getLastSuccessfulBuild();
                        }

                        if (ulb == null) {
                            LOGGER.log(Level.INFO, " -> No, because another upstream " + up + " for " + downstreamProject + " has no successful build");
                            return false;
                        }

                        if (dlb != null) {
                            int n = dlb.getUpstreamRelationship(up);
                            assert n == -1 || ((Run) ulb).getNumber() >= n;
                        }
                    }
                }

                return true;
            }
        }

        private boolean areUpstreamsBuilding(AbstractProject<?, ?> downstreamProject, AbstractProject<?, ?> excludeProject) {
            DependencyGraph graph = Jenkins.getInstance().getDependencyGraph();
            Set<AbstractProject> tups = graph.getTransitiveUpstream(downstreamProject);

            for (AbstractProject tup : tups) {
                if (tup != excludeProject && (tup.isBuilding() || tup.isInQueue())) {
                    return true;
                }
            }

            return false;
        }

        private boolean inDownstreamProjects(AbstractProject<?, ?> downstreamProject) {
            DependencyGraph graph = Jenkins.getInstance().getDependencyGraph();
            Set<AbstractProject> tups = graph.getTransitiveUpstream(downstreamProject);

            for (AbstractProject tup : tups) {
                List<AbstractProject> downstreamProjects = getUpstreamProject().getDownstreamProjects();

                for (AbstractProject dp : downstreamProjects) {
                    if(dp != getUpstreamProject() && dp != downstreamProject && dp == tup) {
                        return true;
                    }
                }
            }

            return false;
        }
    }
}



