package tools.mdsd.pomenhancer;


import org.apache.commons.lang3.ClassUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.shared.model.fileset.FileSet;
import org.apache.maven.shared.model.fileset.util.FileSetManager;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Optional;
import java.util.StringTokenizer;

/**
 * Goal which touches a timestamp file.
 */
@Mojo(name = "enhance-pom", defaultPhase = LifecyclePhase.PACKAGE)
public class POMEnhancerMojo
        extends AbstractMojo {
    /**
     * Location of the file.
     */
    @Parameter(required = true)
    private FileSet[] scanDir;

    @Parameter(required = true)
    private File pomTemplate;

    @Parameter(defaultValue = "false", required = false)
    private boolean failOnParseError = false;

    @Parameter(defaultValue = "false", required = false)
    private boolean failOnMissingValue = false;

    @Parameter(defaultValue = "true", required = false)
    private boolean skipExisting = true;

    @Parameter(required = true)
    private String requiredFields = "";

    @Parameter(defaultValue = ",", required = false)
    private String fieldDelimiter = ",";


    public void execute()
            throws MojoExecutionException, MojoFailureException {
        var fMan = new FileSetManager(getLog());

        for (FileSet fs : scanDir) {
            for (String fileName : fMan.getIncludedFiles(fs)) {
                Path path = Paths.get(fs.getDirectory(), fileName);
                getLog().info("Processing: " + path.toString());
                processArtifact(path);
            }
        }
    }

    private void processArtifact(Path path) throws MojoExecutionException, MojoFailureException {
        if (Files.exists(path) && Files.isRegularFile(path)) {
            var optModel = readModelFromPath(path);
            var optTemplate = readModelFromPath(pomTemplate.toPath());
            if (optModel.isEmpty() || optTemplate.isEmpty()) {
                var errorString = String.format("Unparseable pom artifact: %s", path);
                if (failOnParseError) {
                    throw new MojoFailureException(errorString);
                } else {
                    getLog().debug(errorString);
                }
            } else {
                var processedModel = processModel(optModel.get(), optTemplate.get());
                if (processedModel.isPresent()) {
                    saveModelToPath(processedModel.get(), path);
                }
            }

        } else {
            getLog().debug("Skipped non-existing file: " + path.toString());
        }
    }

    private Optional<Model> readModelFromPath(Path path) throws MojoExecutionException, MojoFailureException {
        MavenXpp3Reader reader = new MavenXpp3Reader();
        try {
            return Optional.ofNullable(reader.read(Files.newInputStream(path)));
        } catch (IOException e) {
            throw new MojoExecutionException("Failure while reading file " + path.toString(), e);
        } catch (XmlPullParserException e) {
            if (failOnParseError) {
                throw new MojoFailureException("Unparseable pom artifact: " + path, e);
            }
        }
        return Optional.empty();
    }

    private Optional<Model> processModel(Model model, Model template) throws MojoExecutionException, MojoFailureException {
        var newModel = model.clone();
        boolean changed = false;

        var tokenizer = new StringTokenizer(requiredFields, fieldDelimiter);

        while (tokenizer.hasMoreElements()) {
            changed |= copyIfRequired(tokenizer.nextToken(), newModel, template);
        }

        return changed ? Optional.of(newModel) : Optional.empty();
    }

    private boolean copyIfRequired(String fieldName, Model target, Model template) throws MojoExecutionException, MojoFailureException {
        Method getterMethod;
        Method setterMethod;
        try {
            getterMethod = Model.class.getDeclaredMethod("get" + StringUtils.capitalize(fieldName));
            setterMethod = Model.class.getDeclaredMethod("set" + StringUtils.capitalize(fieldName),
                    getterMethod.getReturnType());
        } catch (NoSuchMethodException e) {
            throw new MojoExecutionException(String.format("The specified field %s does not exist in class %s.",
                    fieldName, Model.class.getName()), e);
        }

        Object cand = null;
        try {
            cand = getterMethod.invoke(target);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new MojoExecutionException(String.format("Could not invoke getter of field %s for %s",
                    fieldName, target.getClass().getName()), e);
        }

        boolean isPresent = cand != null &&
                ((!(cand instanceof Collection<?>) || ((Collection<?>) cand).isEmpty()));
        if (skipExisting && isPresent) {
            getLog().debug(String.format("Skipping existing entry for \"%s\": %s", fieldName, cand.toString()));
            return false;
        }
        Object entity;
        try {
            entity = getterMethod.invoke(template);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new MojoExecutionException(String.format("Could not invoke getter of field %s for %s",
                    fieldName, template.getClass().getName()), e);
        }

        if (entity == null) {
            String msg = String.format("The template did not contain an entry for \"%s\"", fieldName);
            if (failOnMissingValue) {
                throw new MojoFailureException(msg);
            } else {
                getLog().warn(msg);
            }

            return false;
        }

        Object newEntity = entity;
        if (!(entity instanceof String || ClassUtils.isPrimitiveOrWrapper(entity.getClass()))) {
            try {
                var cloner = entity.getClass().getDeclaredMethod("clone");
                newEntity = cloner.invoke(entity);
            } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
                throw new MojoExecutionException("Uncloneable model artifact encountered. This should not occur.", e);
            }
        }

        getLog().debug(String.format("Setting %s in %s", entity.toString(), target.toString()));
        try {
            setterMethod.invoke(target, newEntity);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new MojoExecutionException(String.format("Could not invoke setter of field %s with value %s for %s",
                    fieldName, newEntity.toString(), target.getClass().getName()), e);
        }
        return true;
    }

    private void saveModelToPath(Model model, Path path) throws MojoExecutionException {
        var writer = new MavenXpp3Writer();
        try {
            writer.write(Files.newOutputStream(path), model);
        } catch (IOException e) {
            throw new MojoExecutionException("Failure while writing file " + path.toString(), e);
        }

    }
}
