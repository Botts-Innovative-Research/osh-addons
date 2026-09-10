package org.sensorhub.impl.comm.reticulum;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class ReticulumNetworkEmbeddedRuntime
{
    public static final String RUNTIME_ID = "embeddedReticulumRuntime";
    public static final String PROCESS_POLICY = "NO_EXTERNAL_PROCESS";
    public static final String SOURCE_MANIFEST = "vendoredSourceManifest";
    public static final String RESOURCE_INDEX = "reticulum/VENDORED-RUNTIME-RESOURCE-INDEX.txt";

    public List<String> bundledProtocols()
    {
        return Collections.unmodifiableList(Arrays.asList("RNS", "LXMF", "LXST"));
    }

    public boolean isSelfSufficient()
    {
        return true;
    }

    public boolean hasVendoredResource(String resourcePath)
    {
        return getClass().getClassLoader().getResource(resourcePath) != null;
    }

    public List<String> resourceIndex() throws IOException
    {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(RESOURCE_INDEX))
        {
            if (input == null)
                throw new IOException("Missing embedded Reticulum runtime resource index");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8)))
            {
                return reader.lines()
                    .filter(line -> !line.trim().isEmpty())
                    .collect(Collectors.toList());
            }
        }
    }

    public Path stageVendoredRuntime(Path targetDirectory) throws IOException
    {
        Path runtimeRoot = targetDirectory.resolve("embeddedReticulumRuntime");
        for (String resource : resourceIndex())
        {
            try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource))
            {
                if (input == null)
                    throw new IOException("Missing embedded runtime resource " + resource);
                Path output = runtimeRoot.resolve(resource);
                Files.createDirectories(output.getParent());
                Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return runtimeRoot;
    }

    public String reticulumPythonPath(Path stagedRuntimeRoot)
    {
        return stagedRuntimeRoot.resolve("reticulum/vendor/Reticulum").toString()
            + java.io.File.pathSeparator
            + stagedRuntimeRoot.resolve("reticulum/vendor/LXMF").toString()
            + java.io.File.pathSeparator
            + stagedRuntimeRoot.resolve("reticulum/vendor/lxst").toString();
    }

    public String describe()
    {
        return RUNTIME_ID + " provides bundled RNS LXMF LXST protocol components without operator-installed services.";
    }
}
