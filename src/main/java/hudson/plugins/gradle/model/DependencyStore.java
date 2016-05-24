package hudson.plugins.gradle.model;

import java.io.*;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class DependencyStore {

    private static final Logger LOGGER = Logger.getLogger(DependencyStore.class.getName());

    public void store(File path, List<GradleDependencyInfo> gradleDependencyInfo) {
        if (gradleDependencyInfo == null)
            return;

        try {
            FileOutputStream outputStream = new FileOutputStream(path);
            ObjectOutputStream out = new ObjectOutputStream(outputStream);
            out.writeObject(gradleDependencyInfo);
            out.close();
            outputStream.close();
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Could not serialize " + path, e);
        }
    }

    public List<GradleDependencyInfo> load(File path) {
        List<GradleDependencyInfo> dependencies = null;

        if (!path.exists())
            return null;

        try {
            FileInputStream inputStream = new FileInputStream(path);
            ObjectInputStream in = new ObjectInputStream(inputStream);
            dependencies = (List<GradleDependencyInfo>) in.readObject();
            in.close();
            inputStream.close();
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Could not load " + path, e);
        }

        return dependencies;
    }
}