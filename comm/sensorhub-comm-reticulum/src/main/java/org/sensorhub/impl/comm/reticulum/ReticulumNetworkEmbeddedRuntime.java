package org.sensorhub.impl.comm.reticulum;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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

    public ImportProbeResult runEmbeddedImportSmoke(Path stagedRuntimeRoot, String pythonExecutable)
        throws IOException, InterruptedException
    {
        String script = ""
            + "import json, importlib\n"
            + "results = {}\n"
            + "for mod in ['RNS','LXMF']:\n"
            + "    try:\n"
            + "        m = importlib.import_module(mod)\n"
            + "        results[mod] = {'ok': True, 'version': getattr(m, '__version__', None)}\n"
            + "    except Exception as e:\n"
            + "        results[mod] = {'ok': False, 'error': type(e).__name__, 'message': str(e)}\n"
            + "try:\n"
            + "    importlib.import_module('LXST')\n"
            + "    results['LXST'] = {'ok': True}\n"
            + "except Exception as e:\n"
            + "    results['LXST'] = {'ok': False, 'error': type(e).__name__, 'message': str(e)}\n"
            + "print(json.dumps(results, sort_keys=True))\n";
        ProcessBuilder builder = new ProcessBuilder(pythonExecutable, "-c", script);
        Map<String, String> environment = builder.environment();
        environment.put("PYTHONPATH", reticulumPythonPath(stagedRuntimeRoot));
        environment.put("PYTHONNOUSERSITE", "1");
        environment.remove("PYTHONHOME");
        Process process = builder.start();
        boolean finished = process.waitFor(Duration.ofSeconds(20).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        if (!finished)
        {
            process.destroyForcibly();
            throw new IOException("Embedded import smoke timed out");
        }
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ImportProbeResult(process.exitValue(), stdout, stderr);
    }

    public ImportProbeResult runEmbeddedProtocolSmoke(Path stagedRuntimeRoot, String pythonExecutable)
        throws IOException, InterruptedException
    {
        String script = ""
            + "import json, traceback\n"
            + "results = {}\n"
            + "try:\n"
            + "    import RNS\n"
            + "    identity = RNS.Identity()\n"
            + "    destination = RNS.Destination(identity, RNS.Destination.OUT, RNS.Destination.SINGLE, 'sensorhub', 'reticulum', 'smoke')\n"
            + "    packet = RNS.Packet(destination, b'osh-reticulum-smoke', create_receipt=False)\n"
            + "    packet.pack()\n"
            + "    results['RNS_PACKET'] = {'ok': True, 'hashLen': len(destination.hash), 'rawLen': len(packet.raw) if packet.raw else 0}\n"
            + "except Exception as e:\n"
            + "    results['RNS_PACKET'] = {'ok': False, 'error': type(e).__name__, 'message': str(e)}\n"
            + "try:\n"
            + "    import RNS, LXMF\n"
            + "    identity = RNS.Identity()\n"
            + "    source = RNS.Destination(identity, RNS.Destination.OUT, RNS.Destination.SINGLE, 'lxmf', 'delivery')\n"
            + "    destination = RNS.Destination(identity, RNS.Destination.OUT, RNS.Destination.SINGLE, 'lxmf', 'delivery')\n"
            + "    message = LXMF.LXMessage(destination, source, 'osh-body', 'osh-title', desired_method=LXMF.LXMessage.DIRECT)\n"
            + "    message.pack()\n"
            + "    results['LXMF_MESSAGE'] = {'ok': True, 'packedLen': len(message.packed) if message.packed else 0, 'title': message.title_as_string(), 'content': message.content_as_string()}\n"
            + "except Exception as e:\n"
            + "    results['LXMF_MESSAGE'] = {'ok': False, 'error': type(e).__name__, 'message': str(e)}\n"
            + "print(json.dumps(results, sort_keys=True))\n";
        ProcessBuilder builder = new ProcessBuilder(pythonExecutable, "-c", script);
        Map<String, String> environment = builder.environment();
        environment.put("PYTHONPATH", reticulumPythonPath(stagedRuntimeRoot));
        environment.put("PYTHONNOUSERSITE", "1");
        environment.remove("PYTHONHOME");
        Process process = builder.start();
        boolean finished = process.waitFor(Duration.ofSeconds(20).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        if (!finished)
        {
            process.destroyForcibly();
            throw new IOException("Embedded protocol smoke timed out");
        }
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ImportProbeResult(process.exitValue(), stdout, stderr);
    }

    public static class ImportProbeResult
    {
        public final int exitCode;
        public final String stdout;
        public final String stderr;

        public ImportProbeResult(int exitCode, String stdout, String stderr)
        {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }
    }

    public String describe()
    {
        return RUNTIME_ID + " provides bundled RNS LXMF LXST protocol components without operator-installed services.";
    }
}
