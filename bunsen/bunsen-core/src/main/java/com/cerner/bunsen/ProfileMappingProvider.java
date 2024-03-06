package com.cerner.bunsen;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.FhirVersionEnum;
import ca.uhn.fhir.context.RuntimeResourceDefinition;
import ca.uhn.fhir.parser.IParser;
import com.cerner.bunsen.exception.ProfileMapperException;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.hl7.fhir.common.hapi.validation.support.PrePopulatedValidationSupport;
import org.hl7.fhir.instance.model.api.IBase;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IPrimitiveType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Class which helps in loading StructureDefinitions into the FhirContext and create the mappings
 * for the resource type to profiles
 */
class ProfileMappingProvider {

  private static final Logger log = LoggerFactory.getLogger(ProfileMappingProvider.class);
  private static final String JSON_EXT = ".json";
  private static String STRUCTURE_DEFINITION = "StructureDefinition";

  /**
   * This method initially loads all the default base structure definitions into the context and
   * additionally loads any custom structure definitions in the given structureDefinitionsPath. A
   * mapping is maintained between the resource type and the profile url that is configured against
   * it. This mapping is initially created using the default base structure definitions and gets
   * overridden with the definitions in the structureDefinitionsPath. In case of multiple structure
   * definitions being defined for the same resource type, lexicographically choose the smaller
   * profile url (which is always deterministic for the given set of definitions).
   *
   * <p>The client can use the mapped profile for the Avro conversion, in case of multiple profiles
   * a better approach would be to come up with a generic solution which can consider all the
   * profiles defined in the structureDefinitionsPath. TODO: Create a generic solution for
   * supporting multiple extensions for same resource type
   * https://github.com/google/fhir-data-pipes/issues/980
   *
   * @param context The context to which the profiles are added.
   * @param structureDefinitionsPath the path containing the list of structure definitions to be
   *     used
   * @return the map containing the resource type to profile mapping.
   * @throws ProfileMapperException if there are any errors while loading and mapping the structure
   *     definitions
   */
  Map<String, String> loadStructureDefinitions(
      FhirContext context, @Nullable String structureDefinitionsPath, boolean isClasspath)
      throws ProfileMapperException {

    // TODO: Add support for other versions (R4B and R5) and then remove this constraint
    // https://github.com/google/fhir-data-pipes/issues/958
    if (context.getVersion().getVersion() != FhirVersionEnum.DSTU3
        && context.getVersion().getVersion() != FhirVersionEnum.R4) {
      String errorMsg =
          String.format(
              "Cannot load FHIR profiles for FhirContext version %s as it is not %s or %s ",
              context.getVersion().getVersion(), FhirVersionEnum.DSTU3, FhirVersionEnum.R4);
      log.error(errorMsg);
      throw new ProfileMapperException(errorMsg);
    }

    PrePopulatedValidationSupport support = new PrePopulatedValidationSupport(context);
    Map<String, String> baseResourcePofileMap = loadBaseStructureDefinitions(context, support);
    if (!Strings.isNullOrEmpty(structureDefinitionsPath)) {
      Map<String, String> customResourceProfileMap =
          loadCustomStructureDefinitions(context, support, structureDefinitionsPath, isClasspath);
      // Overwrite the profiles for the resources with the custom profiles
      baseResourcePofileMap.putAll(customResourceProfileMap);
    }
    context.setValidationSupport(support);
    return baseResourcePofileMap;
  }

  private Map<String, String> loadBaseStructureDefinitions(
      FhirContext context, PrePopulatedValidationSupport support) {
    Map<String, String> resourceProfileMap = new HashMap<>();
    List<IBaseResource> defaultDefinitions =
        context.getValidationSupport().fetchAllStructureDefinitions();
    for (IBaseResource definition : defaultDefinitions) {
      support.addStructureDefinition(definition);
      // Links the profile only if the definition belongs to a base resource. The default
      // definitions loaded could be a StructureDefinition, Extension element, CapabilityStatement,
      // ValueSet etc., hence this check is necessary.
      if (isABaseResource(context, definition)) {
        RuntimeResourceDefinition resourceDefinition = context.getResourceDefinition(definition);
        String type = fetchProperty("type", resourceDefinition, definition);
        String url = fetchProperty("url", resourceDefinition, definition);
        resourceProfileMap.put(type, url);
      }
    }
    context.setValidationSupport(support);
    return resourceProfileMap;
  }

  /**
   * This method loads the custom structure definitions defined in the path
   * structureDefinitionsPath. It fails if any duplicate profile is defined for the same resource
   * type.
   */
  private Map<String, String> loadCustomStructureDefinitions(
      FhirContext context,
      PrePopulatedValidationSupport support,
      String structureDefinitionsPath,
      boolean isClasspath)
      throws ProfileMapperException {
    Map<String, String> resourceProfileMap = new HashMap<>();
    IParser jsonParser = context.newJsonParser();
    try {
      List<IBaseResource> resources =
          isClasspath
              ? getResourcesFromClasspath(jsonParser, structureDefinitionsPath)
              : getResourcesFromPath(jsonParser, structureDefinitionsPath);

      for (IBaseResource baseResource : resources) {
        addDefinitionAndMapping(context, baseResource, support, resourceProfileMap);
      }
    } catch (IOException | URISyntaxException e) {
      String errorMsg =
          String.format(
              "Cannot get the list of files at the directory=%s, classpath=%s, error=%s",
              structureDefinitionsPath, isClasspath, e.getMessage());
      log.error(errorMsg, e);
      throw new ProfileMapperException(errorMsg);
    }
    return resourceProfileMap;
  }

  private List<IBaseResource> getResourcesFromClasspath(IParser parser, String classpath)
      throws ProfileMapperException, URISyntaxException, IOException {

    URL resourceURL = getClass().getResource(classpath);
    if (resourceURL == null) {
      String errorMsg = String.format("the classpath url=%s does not exist", classpath);
      log.error(errorMsg);
      throw new ProfileMapperException(errorMsg);
    }

    URI uri = resourceURL.toURI();
    if (uri.getScheme().equals("jar")) {
      return getResourcesFromJarURL(parser, resourceURL);
    } else {
      return getResourcesFromPath(parser, uri.getSchemeSpecificPart());
    }
  }

  private List<IBaseResource> getResourcesFromJarURL(IParser parser, URL jarURL)
      throws IOException {
    JarURLConnection jarURLConnection = (JarURLConnection) jarURL.openConnection();
    JarFile jarFile = jarURLConnection.getJarFile();
    JarEntry jarEntry = jarURLConnection.getJarEntry();
    String baseEntryPath = (jarEntry != null ? jarEntry.getName() : "");

    List<IBaseResource> resources = new ArrayList<>();
    for (Enumeration<JarEntry> entries = jarFile.entries(); entries.hasMoreElements(); ) {
      JarEntry entry = entries.nextElement();
      String entryPath = entry.getName();
      if (!entry.isDirectory()
          && entryPath.startsWith(baseEntryPath)
          && entryPath.endsWith(JSON_EXT)) {
        resources.add(getResource(parser, jarFile.getInputStream(entry)));
      }
    }
    return resources;
  }

  private List<IBaseResource> getResourcesFromPath(IParser parser, String pathName)
      throws IOException {
    Path path = Paths.get(pathName);
    List<Path> paths = Files.walk(path).collect(Collectors.toList());
    List<Path> definitionPaths = new ArrayList<>();
    paths.stream()
        .filter(f -> f.toString().endsWith(JSON_EXT))
        .forEach(
            f -> {
              definitionPaths.add(f);
            });
    List<IBaseResource> baseResources = new ArrayList<>();
    for (Path definitionPath : definitionPaths) {
      baseResources.add(getResource(parser, definitionPath));
    }
    return baseResources;
  }

  private void addDefinitionAndMapping(
      FhirContext context,
      IBaseResource baseResource,
      PrePopulatedValidationSupport support,
      Map<String, String> resourceProfileMap) {
    RuntimeResourceDefinition resourceDefinition = context.getResourceDefinition(baseResource);
    String resourceName = resourceDefinition.getName();
    if (resourceName.equals(STRUCTURE_DEFINITION)
        && baseResource.getStructureFhirVersionEnum() == context.getVersion().getVersion()) {
      String type = fetchProperty("type", resourceDefinition, baseResource);
      String url = fetchProperty("url", resourceDefinition, baseResource);

      Preconditions.checkNotNull(url, "The url must not be null");
      if (context.getResourceTypes().contains(type)) {
        // Add or replace the url for the resource type with a lexicographically smaller one. This
        // makes sure that for a given set of profiles, it always the chooses the same profile.
        if (resourceProfileMap.get(type) == null
            || resourceProfileMap.get(type).compareTo(url) > 0) {
          resourceProfileMap.put(type, url);
        }
      }
      support.addStructureDefinition(baseResource);
    }
  }

  private static IBaseResource getResource(IParser jsonParser, Path definitionPath)
      throws IOException {
    try (Reader reader = Files.newBufferedReader(definitionPath, StandardCharsets.UTF_8)) {
      return jsonParser.parseResource(reader);
    }
  }

  private static IBaseResource getResource(IParser jsonParser, InputStream inputStream)
      throws IOException {
    try (Reader reader = new BufferedReader(new InputStreamReader(inputStream))) {
      return jsonParser.parseResource(reader);
    }
  }

  private String fetchProperty(
      String property, RuntimeResourceDefinition resourceDefinition, IBaseResource definition) {
    Optional<IBase> propertyValue =
        resourceDefinition.getChildByName(property).getAccessor().getFirstValueOrNull(definition);
    return propertyValue.map((t) -> ((IPrimitiveType) t).getValueAsString()).orElse(null);
  }

  /**
   * Checks if the given resource definition is a StructureDefinition that belongs to a base
   * resource and not an extended definition.
   */
  private boolean isABaseResource(FhirContext fhirContext, IBaseResource definition) {
    RuntimeResourceDefinition resourceDefinition = fhirContext.getResourceDefinition(definition);
    String resourceName = resourceDefinition.getName();
    if (resourceName.equals(STRUCTURE_DEFINITION)) {
      String type = fetchProperty("type", resourceDefinition, definition);
      String baseDefinition = fetchProperty("baseDefinition", resourceDefinition, definition);
      if (fhirContext.getResourceTypes().contains(type)
          && baseDefinition.equalsIgnoreCase(
              "http://hl7.org/fhir/StructureDefinition/DomainResource")) {
        return true;
      }
    }
    return false;
  }
}
