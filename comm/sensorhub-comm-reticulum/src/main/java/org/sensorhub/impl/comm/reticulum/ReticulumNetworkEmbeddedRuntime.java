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
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
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
                if (resource.endsWith("/python/bin/python3") || resource.endsWith("/python/bin/python3.12") || resource.endsWith("/python/python.exe"))
                    output.toFile().setExecutable(true, true);
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

    public Path packagedPythonExecutable(Path stagedRuntimeRoot)
    {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String platform;
        if (osName.contains("win"))
            platform = "windows-x86_64";
        else if (osName.contains("mac") && (arch.contains("aarch64") || arch.contains("arm64")))
            platform = "macos-aarch64";
        else if (osName.contains("mac"))
            platform = "macos-x86_64";
        else if (arch.contains("aarch64") || arch.contains("arm64"))
            platform = "linux-aarch64";
        else
            platform = "linux-x86_64";
        String executable = platform.startsWith("windows") ? "python.exe" : "bin/python3";
        return stagedRuntimeRoot.resolve("reticulum/runtime").resolve(platform).resolve("python").resolve(executable);
    }

    public boolean packagedRuntimeAvailable(Path stagedRuntimeRoot)
    {
        Path executable = packagedPythonExecutable(stagedRuntimeRoot);
        Path executableParent = executable.getParent();
        Path platformRoot = executableParent != null && "bin".equals(executableParent.getFileName().toString())
            ? executableParent.getParent().getParent()
            : executableParent.getParent();
        Path sitePackages = platformRoot == null
            ? null
            : platformRoot.resolve("python/lib/python3.12/site-packages");
        return Files.isRegularFile(executable) && Files.isExecutable(executable)
            && platformRoot != null
            && (Files.isDirectory(platformRoot.resolve("wheelhouse"))
                || (Files.isDirectory(sitePackages.resolve("numpy"))
                    && Files.isDirectory(sitePackages.resolve("pycodec2"))
                    && Files.isDirectory(sitePackages.resolve("cffi"))));
    }

    public ImportProbeResult runPackagedRuntimeSmoke(Path stagedRuntimeRoot)
        throws IOException, InterruptedException
    {
        if (!packagedRuntimeAvailable(stagedRuntimeRoot))
            throw new IOException("SCENARIO-RETICULUM-PACKAGED-RUNTIME missing packagedPythonRuntime or embeddedWheelhouse");
        String script = ""
            + "import json, importlib\n"
            + "results = {}\n"
            + "for mod in ['RNS','LXMF','LXST','numpy','pycodec2','cffi']:\n"
            + "    try:\n"
            + "        m = importlib.import_module(mod)\n"
            + "        results[mod] = {'ok': True, 'version': getattr(m, '__version__', None)}\n"
            + "    except Exception as e:\n"
            + "        results[mod] = {'ok': False, 'error': type(e).__name__, 'message': str(e)}\n"
            + "try:\n"
            + "    import RNS, LXMF\n"
            + "    identity = RNS.Identity()\n"
            + "    destination = RNS.Destination(identity, RNS.Destination.OUT, RNS.Destination.SINGLE, 'sensorhub', 'reticulum', 'smoke')\n"
            + "    packet = RNS.Packet(destination, b'osh-reticulum-smoke', create_receipt=False)\n"
            + "    packet.pack()\n"
            + "    source = RNS.Destination(identity, RNS.Destination.OUT, RNS.Destination.SINGLE, 'lxmf', 'delivery')\n"
            + "    message = LXMF.LXMessage(destination, source, 'osh-body', 'osh-title', desired_method=LXMF.LXMessage.DIRECT)\n"
            + "    message.pack()\n"
            + "    results['PACKAGED_PROTOCOL'] = {'ok': True, 'packetRawLen': len(packet.raw) if packet.raw else 0, 'messagePackedLen': len(message.packed) if message.packed else 0}\n"
            + "except Exception as e:\n"
            + "    results['PACKAGED_PROTOCOL'] = {'ok': False, 'error': type(e).__name__, 'message': str(e)}\n"
            + "print(json.dumps(results, sort_keys=True))\n";
        ProcessBuilder builder = new ProcessBuilder(packagedPythonExecutable(stagedRuntimeRoot).toString(), "-c", script);
        Map<String, String> environment = builder.environment();
        environment.put("PYTHONPATH", reticulumPythonPath(stagedRuntimeRoot));
        environment.put("PYTHONNOUSERSITE", "1");
        environment.remove("PYTHONHOME");
        Process process = builder.start();
        boolean finished = process.waitFor(Duration.ofSeconds(30).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        if (!finished)
        {
            process.destroyForcibly();
            throw new IOException("Packaged runtime smoke timed out");
        }
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ImportProbeResult(process.exitValue(), stdout, stderr);
    }

    public ImportProbeResult runLiveLocalLoopbackSmoke(Path stagedRuntimeRoot)
        throws IOException, InterruptedException
    {
        if (!packagedRuntimeAvailable(stagedRuntimeRoot))
            throw new IOException("SCENARIO-RETICULUM-LIVE-LOCAL-LOOPBACK missing packagedPythonRuntime or embeddedWheelhouse");
        String script = ""
            + "import json, os, socket, subprocess, sys, tempfile, time\n"
            + "def free_port():\n"
            + "    s = socket.socket(); s.bind(('127.0.0.1', 0)); p = s.getsockname()[1]; s.close(); return p\n"
            + "rp, sp = free_port(), free_port()\n"
            + "work = tempfile.mkdtemp(prefix='rns-live-loopback-')\n"
            + "def write_config(name, listen, forward):\n"
            + "    d = os.path.join(work, name); os.makedirs(d)\n"
            + "    with open(os.path.join(d, 'config'), 'w') as f:\n"
            + "        f.write('[reticulum]\\n')\n"
            + "        f.write('enable_transport = No\\n')\n"
            + "        f.write('share_instance = No\\n')\n"
            + "        f.write('shared_instance_port = %d\\n\\n' % free_port())\n"
            + "        f.write('[interfaces]\\n')\n"
            + "        f.write('  [[%s]]\\n' % name)\n"
            + "        f.write('    type = UDPInterface\\n')\n"
            + "        f.write('    enabled = yes\\n')\n"
            + "        f.write('    listen_ip = 127.0.0.1\\n')\n"
            + "        f.write('    listen_port = %d\\n' % listen)\n"
            + "        f.write('    forward_ip = 127.0.0.1\\n')\n"
            + "        f.write('    forward_port = %d\\n' % forward)\n"
            + "    return d\n"
            + "rcfg = write_config('osh_receiver', rp, sp); scfg = write_config('osh_sender', sp, rp)\n"
            + "receiver_code = r'''\n"
            + "import json, sys, time, threading\n"
            + "import RNS\n"
            + "RNS.Reticulum(configdir=sys.argv[1], loglevel=3)\n"
            + "received=[]; event=threading.Event()\n"
            + "identity=RNS.Identity()\n"
            + "dest=RNS.Destination(identity, RNS.Destination.IN, RNS.Destination.SINGLE, 'sensorhub', 'reticulum', 'loopback')\n"
            + "def cb(data, packet):\n"
            + "    received.append(data.decode()); event.set()\n"
            + "dest.set_packet_callback(cb)\n"
            + "print('READY '+RNS.hexrep(dest.hash, delimit=False), flush=True)\n"
            + "end=time.time()+8\n"
            + "while time.time()<end and not event.is_set():\n"
            + "    dest.announce(); time.sleep(0.5)\n"
            + "ok=event.wait(0.1)\n"
            + "print('RESULT '+json.dumps({'ok':ok,'received':received,'destinationHash':RNS.hexrep(dest.hash, delimit=False),'rxPackets':RNS.Transport.rx_packets,'txPackets':RNS.Transport.tx_packets}, sort_keys=True), flush=True)\n"
            + "'''\n"
            + "sender_code = r'''\n"
            + "import json, sys, time\n"
            + "import RNS\n"
            + "configdir=sys.argv[1]; destination_hash=bytes.fromhex(sys.argv[2])\n"
            + "RNS.Reticulum(configdir=configdir, loglevel=3)\n"
            + "if not RNS.Transport.has_path(destination_hash):\n"
            + "    RNS.Transport.request_path(destination_hash)\n"
            + "limit=time.time()+8\n"
            + "while not RNS.Transport.has_path(destination_hash) and time.time()<limit:\n"
            + "    time.sleep(0.2)\n"
            + "path=RNS.Transport.has_path(destination_hash)\n"
            + "identity=RNS.Identity.recall(destination_hash) if path else None\n"
            + "sent=False; packet_hash=None; raw_len=0\n"
            + "if identity:\n"
            + "    dest=RNS.Destination(identity, RNS.Destination.OUT, RNS.Destination.SINGLE, 'sensorhub', 'reticulum', 'loopback')\n"
            + "    packet=RNS.Packet(dest, b'osh-reticulum-live-loopback', create_receipt=False)\n"
            + "    packet.send(); sent=True\n"
            + "    packet_hash=RNS.hexrep(packet.packet_hash, delimit=False) if packet.packet_hash else None\n"
            + "    raw_len=len(packet.raw) if packet.raw else 0\n"
            + "print(json.dumps({'pathResolved':path,'identityRecalled':identity is not None,'sent':sent,'packetHash':packet_hash,'rawLength':raw_len,'rxPackets':RNS.Transport.rx_packets,'txPackets':RNS.Transport.tx_packets}, sort_keys=True), flush=True)\n"
            + "'''\n"
            + "open(os.path.join(work, 'receiver.py'), 'w').write(receiver_code)\n"
            + "open(os.path.join(work, 'sender.py'), 'w').write(sender_code)\n"
            + "env=os.environ.copy(); env['PYTHONNOUSERSITE']='1'; env.pop('PYTHONHOME', None)\n"
            + "receiver=subprocess.Popen([sys.executable, os.path.join(work,'receiver.py'), rcfg], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, env=env)\n"
            + "ready=None; start=time.time()\n"
            + "while time.time()-start<5:\n"
            + "    line=receiver.stdout.readline()\n"
            + "    if line.startswith('READY '): ready=line.strip().split()[1]; break\n"
            + "if not ready:\n"
            + "    receiver.kill(); print(json.dumps({'ok':False,'error':'receiver-not-ready','receiverStderr':receiver.stderr.read()}, sort_keys=True)); sys.exit(2)\n"
            + "sender=subprocess.run([sys.executable, os.path.join(work,'sender.py'), scfg, ready], text=True, capture_output=True, env=env, timeout=15)\n"
            + "out, err = receiver.communicate(timeout=15)\n"
            + "receiver_result=None\n"
            + "for line in out.splitlines():\n"
            + "    if line.startswith('RESULT '): receiver_result=json.loads(line[7:])\n"
            + "sender_result=json.loads(sender.stdout or '{}')\n"
            + "result={'ok': bool(receiver_result and receiver_result.get('ok') and sender.returncode==0 and sender_result.get('pathResolved') and sender_result.get('sent')), 'loopbackMessage':'osh-reticulum-live-loopback', 'receiver':receiver_result, 'sender':sender_result, 'ports':{'receiver':rp,'sender':sp}, 'receiverStderr':err[-2000:], 'senderStderr':sender.stderr[-2000:]}\n"
            + "print(json.dumps(result, sort_keys=True))\n";
        ProcessBuilder builder = new ProcessBuilder(packagedPythonExecutable(stagedRuntimeRoot).toString(), "-c", script);
        Map<String, String> environment = builder.environment();
        environment.put("PYTHONPATH", reticulumPythonPath(stagedRuntimeRoot));
        environment.put("PYTHONNOUSERSITE", "1");
        environment.remove("PYTHONHOME");
        Process process = builder.start();
        boolean finished = process.waitFor(Duration.ofSeconds(30).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        if (!finished)
        {
            process.destroyForcibly();
            throw new IOException("Live local loopback smoke timed out");
        }
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ImportProbeResult(process.exitValue(), stdout, stderr);
    }

    public ImportProbeResult runLiveExtendedProtocolSmoke(Path stagedRuntimeRoot)
        throws IOException, InterruptedException
    {
        if (!packagedRuntimeAvailable(stagedRuntimeRoot))
            throw new IOException("SCENARIO-RETICULUM-LIVE-EXTENDED-PROTOCOLS missing packagedPythonRuntime or embeddedWheelhouse");
        String script = new String(Base64.getDecoder().decode("CmltcG9ydCBqc29uLCBvcywgc29ja2V0LCBzdWJwcm9jZXNzLCBzeXMsIHRlbXBmaWxlLCB0aW1lCgpkZWYgZnJlZV9wb3J0KCk6CiAgICBzID0gc29ja2V0LnNvY2tldCgpCiAgICBzLmJpbmQoKCcxMjcuMC4wLjEnLCAwKSkKICAgIHAgPSBzLmdldHNvY2tuYW1lKClbMV0KICAgIHMuY2xvc2UoKQogICAgcmV0dXJuIHAKCmRlZiB3cml0ZV9jb25maWcocm9vdCwgbmFtZSwgaW50ZXJmYWNlcywgdHJhbnNwb3J0PUZhbHNlKToKICAgIGQgPSBvcy5wYXRoLmpvaW4ocm9vdCwgbmFtZSkKICAgIG9zLm1ha2VkaXJzKGQpCiAgICB3aXRoIG9wZW4ob3MucGF0aC5qb2luKGQsICdjb25maWcnKSwgJ3cnKSBhcyBmOgogICAgICAgIGYud3JpdGUoJ1tyZXRpY3VsdW1dXG4nKQogICAgICAgIGYud3JpdGUoJ2VuYWJsZV90cmFuc3BvcnQgPSAlc1xuJyAlICgnWWVzJyBpZiB0cmFuc3BvcnQgZWxzZSAnTm8nKSkKICAgICAgICBmLndyaXRlKCdzaGFyZV9pbnN0YW5jZSA9IE5vXG4nKQogICAgICAgIGYud3JpdGUoJ3NoYXJlZF9pbnN0YW5jZV9wb3J0ID0gJWRcblxuJyAlIGZyZWVfcG9ydCgpKQogICAgICAgIGYud3JpdGUoJ1tpbnRlcmZhY2VzXVxuJykKICAgICAgICBmb3IgaWZhY2UgaW4gaW50ZXJmYWNlczoKICAgICAgICAgICAgZi53cml0ZSgnICBbWyVzXV1cbicgJSBpZmFjZVsnbmFtZSddKQogICAgICAgICAgICBmLndyaXRlKCcgICAgdHlwZSA9IFVEUEludGVyZmFjZVxuJykKICAgICAgICAgICAgZi53cml0ZSgnICAgIGVuYWJsZWQgPSB5ZXNcbicpCiAgICAgICAgICAgIGYud3JpdGUoJyAgICBsaXN0ZW5faXAgPSAxMjcuMC4wLjFcbicpCiAgICAgICAgICAgIGYud3JpdGUoJyAgICBsaXN0ZW5fcG9ydCA9ICVkXG4nICUgaWZhY2VbJ2xpc3RlbiddKQogICAgICAgICAgICBmLndyaXRlKCcgICAgZm9yd2FyZF9pcCA9IDEyNy4wLjAuMVxuJykKICAgICAgICAgICAgZi53cml0ZSgnICAgIGZvcndhcmRfcG9ydCA9ICVkXG4nICUgaWZhY2VbJ2ZvcndhcmQnXSkKICAgIHJldHVybiBkCgpkZWYgcnVuX2x4bWZfZGVsaXZlcnkoKToKICAgIHJwLCBzcCA9IGZyZWVfcG9ydCgpLCBmcmVlX3BvcnQoKQogICAgd29yayA9IHRlbXBmaWxlLm1rZHRlbXAocHJlZml4PSdseG1mLWxpdmUtZGVsaXZlcnktJykKICAgIHJjZmcgPSB3cml0ZV9jb25maWcod29yaywgJ2x4bWZfcmVjZWl2ZXInLCBbeyduYW1lJzogJ2x4bWZfcmVjZWl2ZXInLCAnbGlzdGVuJzogcnAsICdmb3J3YXJkJzogc3B9XSkKICAgIHNjZmcgPSB3cml0ZV9jb25maWcod29yaywgJ2x4bWZfc2VuZGVyJywgW3snbmFtZSc6ICdseG1mX3NlbmRlcicsICdsaXN0ZW4nOiBzcCwgJ2ZvcndhcmQnOiBycH1dKQogICAgcmVjZWl2ZXJfY29kZSA9ICIiIgppbXBvcnQganNvbiwgc3lzLCB0aW1lLCB0aHJlYWRpbmcKaW1wb3J0IFJOUywgTFhNRgpSTlMuUmV0aWN1bHVtKGNvbmZpZ2Rpcj1zeXMuYXJndlsxXSwgbG9nbGV2ZWw9MykKcmVjZWl2ZWQ9W107IGV2ZW50PXRocmVhZGluZy5FdmVudCgpCmlkZW50aXR5PVJOUy5JZGVudGl0eSgpCnJvdXRlcj1MWE1GLkxYTVJvdXRlcihzdG9yYWdlcGF0aD1zeXMuYXJndlsxXSwgYXV0b3BlZXI9RmFsc2UsIGVuZm9yY2Vfc3RhbXBzPUZhbHNlKQpkZXN0PXJvdXRlci5yZWdpc3Rlcl9kZWxpdmVyeV9pZGVudGl0eShpZGVudGl0eSwgZGlzcGxheV9uYW1lPSdPU0ggTFhNRiBSZWNlaXZlcicsIHN0YW1wX2Nvc3Q9MCkKZGVmIGNiKG1lc3NhZ2UpOgogICAgcmVjZWl2ZWQuYXBwZW5kKHsndGl0bGUnOiBtZXNzYWdlLnRpdGxlX2FzX3N0cmluZygpLCAnY29udGVudCc6IG1lc3NhZ2UuY29udGVudF9hc19zdHJpbmcoKSwgJ21ldGhvZCc6IG1lc3NhZ2UubWV0aG9kLCAnc2lnbmF0dXJlVmFsaWRhdGVkJzogYm9vbChnZXRhdHRyKG1lc3NhZ2UsICdzaWduYXR1cmVfdmFsaWRhdGVkJywgRmFsc2UpKX0pCiAgICBldmVudC5zZXQoKQpyb3V0ZXIucmVnaXN0ZXJfZGVsaXZlcnlfY2FsbGJhY2soY2IpCnByaW50KCdSRUFEWSAnK1JOUy5oZXhyZXAoZGVzdC5oYXNoLCBkZWxpbWl0PUZhbHNlKSwgZmx1c2g9VHJ1ZSkKZW5kPXRpbWUudGltZSgpKzkKd2hpbGUgdGltZS50aW1lKCk8ZW5kIGFuZCBub3QgZXZlbnQuaXNfc2V0KCk6CiAgICByb3V0ZXIuYW5ub3VuY2UoZGVzdC5oYXNoKTsgdGltZS5zbGVlcCgwLjUpCm9rPWV2ZW50LndhaXQoMC4xKQpwcmludCgnUkVTVUxUICcranNvbi5kdW1wcyh7J29rJzogb2ssICdyZWNlaXZlZCc6IHJlY2VpdmVkLCAnZGVzdGluYXRpb25IYXNoJzogUk5TLmhleHJlcChkZXN0Lmhhc2gsIGRlbGltaXQ9RmFsc2UpLCAncnhQYWNrZXRzJzogUk5TLlRyYW5zcG9ydC5yeF9wYWNrZXRzLCAndHhQYWNrZXRzJzogUk5TLlRyYW5zcG9ydC50eF9wYWNrZXRzfSwgc29ydF9rZXlzPVRydWUpLCBmbHVzaD1UcnVlKQoiIiIKICAgIHNlbmRlcl9jb2RlID0gIiIiCmltcG9ydCBqc29uLCBzeXMsIHRpbWUKaW1wb3J0IFJOUywgTFhNRgpjb25maWdkaXI9c3lzLmFyZ3ZbMV07IGRlc3RpbmF0aW9uX2hhc2g9Ynl0ZXMuZnJvbWhleChzeXMuYXJndlsyXSkKUk5TLlJldGljdWx1bShjb25maWdkaXI9Y29uZmlnZGlyLCBsb2dsZXZlbD0zKQpyb3V0ZXI9TFhNRi5MWE1Sb3V0ZXIoc3RvcmFnZXBhdGg9Y29uZmlnZGlyLCBhdXRvcGVlcj1GYWxzZSwgZW5mb3JjZV9zdGFtcHM9RmFsc2UpCmlmIG5vdCBSTlMuVHJhbnNwb3J0Lmhhc19wYXRoKGRlc3RpbmF0aW9uX2hhc2gpOgogICAgUk5TLlRyYW5zcG9ydC5yZXF1ZXN0X3BhdGgoZGVzdGluYXRpb25faGFzaCkKbGltaXQ9dGltZS50aW1lKCkrOQp3aGlsZSBub3QgUk5TLlRyYW5zcG9ydC5oYXNfcGF0aChkZXN0aW5hdGlvbl9oYXNoKSBhbmQgdGltZS50aW1lKCk8bGltaXQ6CiAgICB0aW1lLnNsZWVwKDAuMikKcGF0aD1STlMuVHJhbnNwb3J0Lmhhc19wYXRoKGRlc3RpbmF0aW9uX2hhc2gpCmlkZW50aXR5PVJOUy5JZGVudGl0eS5yZWNhbGwoZGVzdGluYXRpb25faGFzaCkgaWYgcGF0aCBlbHNlIE5vbmUKc2VudD1GYWxzZTsgc3RhdGU9Tm9uZTsgcGFja2VkX2xlbj0wOyBtZXNzYWdlX2hhc2g9Tm9uZQppZiBpZGVudGl0eToKICAgIGRlc3Q9Uk5TLkRlc3RpbmF0aW9uKGlkZW50aXR5LCBSTlMuRGVzdGluYXRpb24uT1VULCBSTlMuRGVzdGluYXRpb24uU0lOR0xFLCAnbHhtZicsICdkZWxpdmVyeScpCiAgICBzcmNfaWQ9Uk5TLklkZW50aXR5KCkKICAgIHNyYz1STlMuRGVzdGluYXRpb24oc3JjX2lkLCBSTlMuRGVzdGluYXRpb24uT1VULCBSTlMuRGVzdGluYXRpb24uU0lOR0xFLCAnbHhtZicsICdkZWxpdmVyeScpCiAgICBtc2c9TFhNRi5MWE1lc3NhZ2UoZGVzdCwgc3JjLCAnb3NoLWx4bWYtbGl2ZS1kZWxpdmVyeScsICdPU0ggTFhNRiBMb29wYmFjaycsIGRlc2lyZWRfbWV0aG9kPUxYTUYuTFhNZXNzYWdlLk9QUE9SVFVOSVNUSUMsIGluY2x1ZGVfdGlja2V0PUZhbHNlKQogICAgcm91dGVyLmhhbmRsZV9vdXRib3VuZChtc2cpCiAgICBzZW50PVRydWU7IHN0YXRlPW1zZy5zdGF0ZTsgcGFja2VkX2xlbj1sZW4obXNnLnBhY2tlZCkgaWYgbXNnLnBhY2tlZCBlbHNlIDA7IG1lc3NhZ2VfaGFzaD1STlMuaGV4cmVwKG1zZy5oYXNoLCBkZWxpbWl0PUZhbHNlKSBpZiBtc2cuaGFzaCBlbHNlIE5vbmUKcHJpbnQoanNvbi5kdW1wcyh7J3BhdGhSZXNvbHZlZCc6IHBhdGgsICdpZGVudGl0eVJlY2FsbGVkJzogaWRlbnRpdHkgaXMgbm90IE5vbmUsICdzZW50Jzogc2VudCwgJ3N0YXRlJzogc3RhdGUsICdwYWNrZWRMZW5ndGgnOiBwYWNrZWRfbGVuLCAnbWVzc2FnZUhhc2gnOiBtZXNzYWdlX2hhc2gsICdyeFBhY2tldHMnOiBSTlMuVHJhbnNwb3J0LnJ4X3BhY2tldHMsICd0eFBhY2tldHMnOiBSTlMuVHJhbnNwb3J0LnR4X3BhY2tldHN9LCBzb3J0X2tleXM9VHJ1ZSksIGZsdXNoPVRydWUpCiIiIgogICAgb3Blbihvcy5wYXRoLmpvaW4od29yaywgJ3JlY2VpdmVyLnB5JyksICd3Jykud3JpdGUocmVjZWl2ZXJfY29kZSkKICAgIG9wZW4ob3MucGF0aC5qb2luKHdvcmssICdzZW5kZXIucHknKSwgJ3cnKS53cml0ZShzZW5kZXJfY29kZSkKICAgIGVudj1vcy5lbnZpcm9uLmNvcHkoKTsgZW52WydQWVRIT05OT1VTRVJTSVRFJ109JzEnOyBlbnYucG9wKCdQWVRIT05IT01FJywgTm9uZSkKICAgIHJlY2VpdmVyPXN1YnByb2Nlc3MuUG9wZW4oW3N5cy5leGVjdXRhYmxlLCBvcy5wYXRoLmpvaW4od29yaywncmVjZWl2ZXIucHknKSwgcmNmZ10sIHN0ZG91dD1zdWJwcm9jZXNzLlBJUEUsIHN0ZGVycj1zdWJwcm9jZXNzLlBJUEUsIHRleHQ9VHJ1ZSwgZW52PWVudikKICAgIHJlYWR5PU5vbmU7IHN0YXJ0PXRpbWUudGltZSgpCiAgICB3aGlsZSB0aW1lLnRpbWUoKS1zdGFydDw2OgogICAgICAgIGxpbmU9cmVjZWl2ZXIuc3Rkb3V0LnJlYWRsaW5lKCkKICAgICAgICBpZiBsaW5lLnN0YXJ0c3dpdGgoJ1JFQURZICcpOgogICAgICAgICAgICByZWFkeT1saW5lLnN0cmlwKCkuc3BsaXQoKVsxXTsgYnJlYWsKICAgIGlmIG5vdCByZWFkeToKICAgICAgICByZWNlaXZlci5raWxsKCkKICAgICAgICByZXR1cm4geydvayc6IEZhbHNlLCAnZXJyb3InOiAnbHhtZi1yZWNlaXZlci1ub3QtcmVhZHknLCAncmVjZWl2ZXJTdGRlcnInOiByZWNlaXZlci5zdGRlcnIucmVhZCgpWy0yMDAwOl19CiAgICBzZW5kZXI9c3VicHJvY2Vzcy5ydW4oW3N5cy5leGVjdXRhYmxlLCBvcy5wYXRoLmpvaW4od29yaywnc2VuZGVyLnB5JyksIHNjZmcsIHJlYWR5XSwgdGV4dD1UcnVlLCBjYXB0dXJlX291dHB1dD1UcnVlLCBlbnY9ZW52LCB0aW1lb3V0PTE4KQogICAgb3V0LCBlcnIgPSByZWNlaXZlci5jb21tdW5pY2F0ZSh0aW1lb3V0PTE4KQogICAgcmVjZWl2ZXJfcmVzdWx0PU5vbmUKICAgIGZvciBsaW5lIGluIG91dC5zcGxpdGxpbmVzKCk6CiAgICAgICAgaWYgbGluZS5zdGFydHN3aXRoKCdSRVNVTFQgJyk6IHJlY2VpdmVyX3Jlc3VsdD1qc29uLmxvYWRzKGxpbmVbNzpdKQogICAgdHJ5OgogICAgICAgIHNlbmRlcl9yZXN1bHQ9anNvbi5sb2FkcyhzZW5kZXIuc3Rkb3V0IG9yICd7fScpCiAgICBleGNlcHQgRXhjZXB0aW9uIGFzIGU6CiAgICAgICAgc2VuZGVyX3Jlc3VsdD17J3BhcnNlRXJyb3InOiB0eXBlKGUpLl9fbmFtZV9fLCAnc3Rkb3V0Jzogc2VuZGVyLnN0ZG91dH0KICAgIHJlY2VpdmVkX3RleHRzPVttLmdldCgnY29udGVudCcpIGZvciBtIGluIChyZWNlaXZlcl9yZXN1bHQgb3Ige30pLmdldCgncmVjZWl2ZWQnLCBbXSldCiAgICByZXR1cm4geydvayc6IGJvb2wocmVjZWl2ZXJfcmVzdWx0IGFuZCByZWNlaXZlcl9yZXN1bHQuZ2V0KCdvaycpIGFuZCBzZW5kZXIucmV0dXJuY29kZT09MCBhbmQgc2VuZGVyX3Jlc3VsdC5nZXQoJ3BhdGhSZXNvbHZlZCcpIGFuZCBzZW5kZXJfcmVzdWx0LmdldCgnc2VudCcpIGFuZCAnb3NoLWx4bWYtbGl2ZS1kZWxpdmVyeScgaW4gcmVjZWl2ZWRfdGV4dHMpLCAncmVjZWl2ZXInOiByZWNlaXZlcl9yZXN1bHQsICdzZW5kZXInOiBzZW5kZXJfcmVzdWx0LCAncG9ydHMnOiB7J3JlY2VpdmVyJzogcnAsICdzZW5kZXInOiBzcH0sICdyZWNlaXZlclN0ZGVycic6IGVyclstMjAwMDpdLCAnc2VuZGVyU3RkZXJyJzogc2VuZGVyLnN0ZGVyclstMjAwMDpdfQoKZGVmIHJ1bl9seHN0X3N0cmVhbWluZygpOgogICAgaW1wb3J0IG51bXB5IGFzIG5wCiAgICBmcm9tIExYU1QuQ29kZWNzIGltcG9ydCBSYXcKICAgIGZyb20gTFhTVC5QaXBlbGluZSBpbXBvcnQgUGlwZWxpbmUKICAgIGZyb20gTFhTVC5HZW5lcmF0b3JzIGltcG9ydCBUb25lU291cmNlCiAgICBmcm9tIExYU1QuU2lua3MgaW1wb3J0IExvY2FsU2luawogICAgY29kZWMgPSBSYXcoKQogICAgZnJhbWVzPVtdCiAgICBjbGFzcyBNZW1vcnlTaW5rKExvY2FsU2luayk6CiAgICAgICAgZGVmIF9faW5pdF9fKHNlbGYpOiBzZWxmLnBpcGVsaW5lPU5vbmUKICAgICAgICBkZWYgc3RhcnQoc2VsZik6IHJldHVybiBOb25lCiAgICAgICAgZGVmIHN0b3Aoc2VsZik6IHJldHVybiBOb25lCiAgICAgICAgZGVmIGhhbmRsZV9mcmFtZShzZWxmLCBkYXRhLCBzb3VyY2U9Tm9uZSk6CiAgICAgICAgICAgIGRlY29kZWQgPSBjb2RlYy5kZWNvZGUoZGF0YSkgaWYgaXNpbnN0YW5jZShkYXRhLCAoYnl0ZXMsIGJ5dGVhcnJheSkpIGVsc2UgZGF0YQogICAgICAgICAgICBmcmFtZXMuYXBwZW5kKHsnZW5jb2RlZEJ5dGVzJzogbGVuKGRhdGEpIGlmIGlzaW5zdGFuY2UoZGF0YSwgKGJ5dGVzLCBieXRlYXJyYXkpKSBlbHNlIE5vbmUsICdkZWNvZGVkU2hhcGUnOiBsaXN0KGRlY29kZWQuc2hhcGUpLCAnc3VtJzogZmxvYXQobnAuc3VtKGRlY29kZWQpKSwgJ21pbic6IGZsb2F0KG5wLm1pbihkZWNvZGVkKSksICdtYXgnOiBmbG9hdChucC5tYXgoZGVjb2RlZCkpfSkKICAgIHNpbms9TWVtb3J5U2luaygpCiAgICBwaXBlbGluZT1QaXBlbGluZShUb25lU291cmNlKGZyZXF1ZW5jeT0xMDAwLCBnYWluPTAuMSwgdGFyZ2V0X2ZyYW1lX21zPTIwLCBlYXNlPUZhbHNlKSwgY29kZWMsIHNpbmspCiAgICBwaXBlbGluZS5zdGFydCgpOyB0aW1lLnNsZWVwKDAuOCk7IHBpcGVsaW5lLnN0b3AoKQogICAgcmV0dXJuIHsnY29kZWMnOiAnUmF3JywgJ3NvdXJjZSc6ICdUb25lU291cmNlJywgJ3NpbmsnOiAnTWVtb3J5U2luaycsICdzYW1wbGVyYXRlJzogNDgwMDAsICd0YXJnZXRGcmFtZU1zJzogMjAsICdmcmFtZUNvdW50JzogbGVuKGZyYW1lcyksICdmaXJzdEZyYW1lJzogZnJhbWVzWzBdIGlmIGZyYW1lcyBlbHNlIE5vbmUsICdwaXBlbGluZVJ1bm5pbmdBZnRlclN0b3AnOiBib29sKGdldGF0dHIocGlwZWxpbmUsICdydW5uaW5nJywgRmFsc2UpKSwgJ29rJzogbGVuKGZyYW1lcykgPj0gMiBhbmQgZnJhbWVzWzBdWydkZWNvZGVkU2hhcGUnXSA9PSBbOTYwLCAxXSBhbmQgbm90IGJvb2woZ2V0YXR0cihwaXBlbGluZSwgJ3J1bm5pbmcnLCBGYWxzZSkpfQoKZGVmIHJ1bl9yb3V0ZWRfcGVlcigpOgogICAgcnAsIHJwYSwgc3BhLCBzcCA9IGZyZWVfcG9ydCgpLCBmcmVlX3BvcnQoKSwgZnJlZV9wb3J0KCksIGZyZWVfcG9ydCgpCiAgICB3b3JrPXRlbXBmaWxlLm1rZHRlbXAocHJlZml4PSdybnMtcm91dGVkLXBlZXItJykKICAgIHJjZmc9d3JpdGVfY29uZmlnKHdvcmssICdyb3V0ZWRfcmVjZWl2ZXInLCBbeyduYW1lJzoncmVjZWl2ZXJfc2VnbWVudCcsJ2xpc3Rlbic6cnAsJ2ZvcndhcmQnOnJwYX1dKQogICAgcm91dGVyY2ZnPXdyaXRlX2NvbmZpZyh3b3JrLCAncm91dGVkX3JvdXRlcicsIFt7J25hbWUnOidyb3V0ZXJfdG9fcmVjZWl2ZXInLCdsaXN0ZW4nOnJwYSwnZm9yd2FyZCc6cnB9LHsnbmFtZSc6J3JvdXRlcl90b19zZW5kZXInLCdsaXN0ZW4nOnNwYSwnZm9yd2FyZCc6c3B9XSwgdHJhbnNwb3J0PVRydWUpCiAgICBzY2ZnPXdyaXRlX2NvbmZpZyh3b3JrLCAncm91dGVkX3NlbmRlcicsIFt7J25hbWUnOidzZW5kZXJfc2VnbWVudCcsJ2xpc3Rlbic6c3AsJ2ZvcndhcmQnOnNwYX1dKQogICAgcmVjZWl2ZXJfY29kZT0iIiIKaW1wb3J0IGpzb24sIHN5cywgdGltZSwgdGhyZWFkaW5nCmltcG9ydCBSTlMKUk5TLlJldGljdWx1bShjb25maWdkaXI9c3lzLmFyZ3ZbMV0sIGxvZ2xldmVsPTMpCnJlY2VpdmVkPVtdOyBldmVudD10aHJlYWRpbmcuRXZlbnQoKTsgaWRlbnRpdHk9Uk5TLklkZW50aXR5KCkKZGVzdD1STlMuRGVzdGluYXRpb24oaWRlbnRpdHksIFJOUy5EZXN0aW5hdGlvbi5JTiwgUk5TLkRlc3RpbmF0aW9uLlNJTkdMRSwgJ3NlbnNvcmh1YicsICdyZXRpY3VsdW0nLCAncm91dGVkJykKZGVmIGNiKGRhdGEsIHBhY2tldCk6IHJlY2VpdmVkLmFwcGVuZChkYXRhLmRlY29kZSgpKTsgZXZlbnQuc2V0KCkKZGVzdC5zZXRfcGFja2V0X2NhbGxiYWNrKGNiKQpwcmludCgnUkVBRFkgJytSTlMuaGV4cmVwKGRlc3QuaGFzaCwgZGVsaW1pdD1GYWxzZSksIGZsdXNoPVRydWUpCmVuZD10aW1lLnRpbWUoKSsxMgp3aGlsZSB0aW1lLnRpbWUoKTxlbmQgYW5kIG5vdCBldmVudC5pc19zZXQoKTogZGVzdC5hbm5vdW5jZSgpOyB0aW1lLnNsZWVwKDAuNikKb2s9ZXZlbnQud2FpdCgwLjEpCnByaW50KCdSRVNVTFQgJytqc29uLmR1bXBzKHsnb2snOiBvaywgJ3JlY2VpdmVkJzogcmVjZWl2ZWQsICdkZXN0aW5hdGlvbkhhc2gnOiBSTlMuaGV4cmVwKGRlc3QuaGFzaCwgZGVsaW1pdD1GYWxzZSksICdyeFBhY2tldHMnOiBSTlMuVHJhbnNwb3J0LnJ4X3BhY2tldHMsICd0eFBhY2tldHMnOiBSTlMuVHJhbnNwb3J0LnR4X3BhY2tldHN9LCBzb3J0X2tleXM9VHJ1ZSksIGZsdXNoPVRydWUpCiIiIgogICAgcm91dGVyX2NvZGU9IiIiCmltcG9ydCBqc29uLCBzeXMsIHRpbWUKaW1wb3J0IFJOUwpSTlMuUmV0aWN1bHVtKGNvbmZpZ2Rpcj1zeXMuYXJndlsxXSwgbG9nbGV2ZWw9MykKdGltZS5zbGVlcChmbG9hdChzeXMuYXJndlsyXSkpCmludGVyZmFjZXM9W3N0cihpKSBmb3IgaSBpbiBSTlMuVHJhbnNwb3J0LmludGVyZmFjZXNdCnByaW50KCdSRVNVTFQgJytqc29uLmR1bXBzKHsnaW50ZXJmYWNlcyc6IGludGVyZmFjZXMsICdyeFBhY2tldHMnOiBSTlMuVHJhbnNwb3J0LnJ4X3BhY2tldHMsICd0eFBhY2tldHMnOiBSTlMuVHJhbnNwb3J0LnR4X3BhY2tldHN9LCBzb3J0X2tleXM9VHJ1ZSksIGZsdXNoPVRydWUpCiIiIgogICAgc2VuZGVyX2NvZGU9IiIiCmltcG9ydCBqc29uLCBzeXMsIHRpbWUKaW1wb3J0IFJOUwpjb25maWdkaXI9c3lzLmFyZ3ZbMV07IGRlc3RpbmF0aW9uX2hhc2g9Ynl0ZXMuZnJvbWhleChzeXMuYXJndlsyXSkKUk5TLlJldGljdWx1bShjb25maWdkaXI9Y29uZmlnZGlyLCBsb2dsZXZlbD0zKQppZiBub3QgUk5TLlRyYW5zcG9ydC5oYXNfcGF0aChkZXN0aW5hdGlvbl9oYXNoKTogUk5TLlRyYW5zcG9ydC5yZXF1ZXN0X3BhdGgoZGVzdGluYXRpb25faGFzaCkKbGltaXQ9dGltZS50aW1lKCkrMTAKd2hpbGUgbm90IFJOUy5UcmFuc3BvcnQuaGFzX3BhdGgoZGVzdGluYXRpb25faGFzaCkgYW5kIHRpbWUudGltZSgpPGxpbWl0OiB0aW1lLnNsZWVwKDAuMikKcGF0aD1STlMuVHJhbnNwb3J0Lmhhc19wYXRoKGRlc3RpbmF0aW9uX2hhc2gpOyBpZGVudGl0eT1STlMuSWRlbnRpdHkucmVjYWxsKGRlc3RpbmF0aW9uX2hhc2gpIGlmIHBhdGggZWxzZSBOb25lCnNlbnQ9RmFsc2U7IHJhd19sZW49MAppZiBpZGVudGl0eToKICAgIGRlc3Q9Uk5TLkRlc3RpbmF0aW9uKGlkZW50aXR5LCBSTlMuRGVzdGluYXRpb24uT1VULCBSTlMuRGVzdGluYXRpb24uU0lOR0xFLCAnc2Vuc29yaHViJywgJ3JldGljdWx1bScsICdyb3V0ZWQnKQogICAgcGFja2V0PVJOUy5QYWNrZXQoZGVzdCwgYidvc2gtcmV0aWN1bHVtLXJvdXRlZC1wZWVyJywgY3JlYXRlX3JlY2VpcHQ9RmFsc2UpOyBwYWNrZXQuc2VuZCgpOyBzZW50PVRydWU7IHJhd19sZW49bGVuKHBhY2tldC5yYXcpIGlmIHBhY2tldC5yYXcgZWxzZSAwCnByaW50KGpzb24uZHVtcHMoeydwYXRoUmVzb2x2ZWQnOiBwYXRoLCAnaWRlbnRpdHlSZWNhbGxlZCc6IGlkZW50aXR5IGlzIG5vdCBOb25lLCAnc2VudCc6IHNlbnQsICdyYXdMZW5ndGgnOiByYXdfbGVuLCAncnhQYWNrZXRzJzogUk5TLlRyYW5zcG9ydC5yeF9wYWNrZXRzLCAndHhQYWNrZXRzJzogUk5TLlRyYW5zcG9ydC50eF9wYWNrZXRzfSwgc29ydF9rZXlzPVRydWUpLCBmbHVzaD1UcnVlKQoiIiIKICAgIGZvciBuYW1lLCBjb2RlIGluIFsoJ3JlY2VpdmVyLnB5JywgcmVjZWl2ZXJfY29kZSksICgncm91dGVyLnB5Jywgcm91dGVyX2NvZGUpLCAoJ3NlbmRlci5weScsIHNlbmRlcl9jb2RlKV06CiAgICAgICAgb3Blbihvcy5wYXRoLmpvaW4od29yaywgbmFtZSksICd3Jykud3JpdGUoY29kZSkKICAgIGVudj1vcy5lbnZpcm9uLmNvcHkoKTsgZW52WydQWVRIT05OT1VTRVJTSVRFJ109JzEnOyBlbnYucG9wKCdQWVRIT05IT01FJywgTm9uZSkKICAgIHJvdXRlcj1zdWJwcm9jZXNzLlBvcGVuKFtzeXMuZXhlY3V0YWJsZSwgb3MucGF0aC5qb2luKHdvcmssJ3JvdXRlci5weScpLCByb3V0ZXJjZmcsICcxNCddLCBzdGRvdXQ9c3VicHJvY2Vzcy5QSVBFLCBzdGRlcnI9c3VicHJvY2Vzcy5QSVBFLCB0ZXh0PVRydWUsIGVudj1lbnYpCiAgICB0aW1lLnNsZWVwKDAuNSkKICAgIHJlY2VpdmVyPXN1YnByb2Nlc3MuUG9wZW4oW3N5cy5leGVjdXRhYmxlLCBvcy5wYXRoLmpvaW4od29yaywncmVjZWl2ZXIucHknKSwgcmNmZ10sIHN0ZG91dD1zdWJwcm9jZXNzLlBJUEUsIHN0ZGVycj1zdWJwcm9jZXNzLlBJUEUsIHRleHQ9VHJ1ZSwgZW52PWVudikKICAgIHJlYWR5PU5vbmU7IHN0YXJ0PXRpbWUudGltZSgpCiAgICB3aGlsZSB0aW1lLnRpbWUoKS1zdGFydDw3OgogICAgICAgIGxpbmU9cmVjZWl2ZXIuc3Rkb3V0LnJlYWRsaW5lKCkKICAgICAgICBpZiBsaW5lLnN0YXJ0c3dpdGgoJ1JFQURZICcpOiByZWFkeT1saW5lLnN0cmlwKCkuc3BsaXQoKVsxXTsgYnJlYWsKICAgIGlmIG5vdCByZWFkeToKICAgICAgICByZWNlaXZlci5raWxsKCk7IHJvdXRlci5raWxsKCkKICAgICAgICByZXR1cm4geydvayc6IEZhbHNlLCAnZXJyb3InOiAncm91dGVkLXJlY2VpdmVyLW5vdC1yZWFkeScsICdyZWNlaXZlclN0ZGVycic6IHJlY2VpdmVyLnN0ZGVyci5yZWFkKClbLTIwMDA6XSwgJ3JvdXRlclN0ZGVycic6IHJvdXRlci5zdGRlcnIucmVhZCgpWy0yMDAwOl19CiAgICBzZW5kZXI9c3VicHJvY2Vzcy5ydW4oW3N5cy5leGVjdXRhYmxlLCBvcy5wYXRoLmpvaW4od29yaywnc2VuZGVyLnB5JyksIHNjZmcsIHJlYWR5XSwgdGV4dD1UcnVlLCBjYXB0dXJlX291dHB1dD1UcnVlLCBlbnY9ZW52LCB0aW1lb3V0PTIwKQogICAgcm91dF9vdXQsIHJvdXRfZXJyID0gcm91dGVyLmNvbW11bmljYXRlKHRpbWVvdXQ9MjApCiAgICByZWN2X291dCwgcmVjdl9lcnIgPSByZWNlaXZlci5jb21tdW5pY2F0ZSh0aW1lb3V0PTIwKQogICAgcmVjZWl2ZXJfcmVzdWx0PU5vbmU7IHJvdXRlcl9yZXN1bHQ9Tm9uZQogICAgZm9yIGxpbmUgaW4gcmVjdl9vdXQuc3BsaXRsaW5lcygpOgogICAgICAgIGlmIGxpbmUuc3RhcnRzd2l0aCgnUkVTVUxUICcpOiByZWNlaXZlcl9yZXN1bHQ9anNvbi5sb2FkcyhsaW5lWzc6XSkKICAgIGZvciBsaW5lIGluIHJvdXRfb3V0LnNwbGl0bGluZXMoKToKICAgICAgICBpZiBsaW5lLnN0YXJ0c3dpdGgoJ1JFU1VMVCAnKTogcm91dGVyX3Jlc3VsdD1qc29uLmxvYWRzKGxpbmVbNzpdKQogICAgdHJ5OiBzZW5kZXJfcmVzdWx0PWpzb24ubG9hZHMoc2VuZGVyLnN0ZG91dCBvciAne30nKQogICAgZXhjZXB0IEV4Y2VwdGlvbiBhcyBlOiBzZW5kZXJfcmVzdWx0PXsncGFyc2VFcnJvcic6IHR5cGUoZSkuX19uYW1lX18sICdzdGRvdXQnOiBzZW5kZXIuc3Rkb3V0fQogICAgaW50ZXJmYWNlcz0nICcuam9pbigocm91dGVyX3Jlc3VsdCBvciB7fSkuZ2V0KCdpbnRlcmZhY2VzJywgW10pKQogICAgcmV0dXJuIHsnb2snOiBib29sKHJlY2VpdmVyX3Jlc3VsdCBhbmQgcmVjZWl2ZXJfcmVzdWx0LmdldCgnb2snKSBhbmQgc2VuZGVyLnJldHVybmNvZGU9PTAgYW5kIHNlbmRlcl9yZXN1bHQuZ2V0KCdwYXRoUmVzb2x2ZWQnKSBhbmQgc2VuZGVyX3Jlc3VsdC5nZXQoJ3NlbnQnKSBhbmQgJ29zaC1yZXRpY3VsdW0tcm91dGVkLXBlZXInIGluIHJlY2VpdmVyX3Jlc3VsdC5nZXQoJ3JlY2VpdmVkJywgW10pIGFuZCAncm91dGVyX3RvX3JlY2VpdmVyJyBpbiBpbnRlcmZhY2VzIGFuZCAncm91dGVyX3RvX3NlbmRlcicgaW4gaW50ZXJmYWNlcyksICdyZWNlaXZlcic6IHJlY2VpdmVyX3Jlc3VsdCwgJ3NlbmRlcic6IHNlbmRlcl9yZXN1bHQsICdyb3V0ZXInOiByb3V0ZXJfcmVzdWx0LCAncG9ydHMnOiB7J3JlY2VpdmVyJzogcnAsICdyb3V0ZXJSZWNlaXZlclNpZGUnOiBycGEsICdyb3V0ZXJTZW5kZXJTaWRlJzogc3BhLCAnc2VuZGVyJzogc3B9LCAncmVjZWl2ZXJTdGRlcnInOiByZWN2X2VyclstMjAwMDpdLCAnc2VuZGVyU3RkZXJyJzogc2VuZGVyLnN0ZGVyclstMjAwMDpdLCAncm91dGVyU3RkZXJyJzogcm91dF9lcnJbLTIwMDA6XX0KCnJlc3VsdHMgPSB7CiAgICAnTFhNRl9ERUxJVkVSWSc6IHJ1bl9seG1mX2RlbGl2ZXJ5KCksCiAgICAnTFhTVF9TVFJFQU1JTkcnOiBydW5fbHhzdF9zdHJlYW1pbmcoKSwKICAgICdSTlNfUk9VVEVEX1BFRVInOiBydW5fcm91dGVkX3BlZXIoKSwKfQpyZXN1bHRzWydvayddID0gYWxsKHYuZ2V0KCdvaycpIGZvciB2IGluIHJlc3VsdHMudmFsdWVzKCkpCnByaW50KGpzb24uZHVtcHMocmVzdWx0cywgc29ydF9rZXlzPVRydWUpKQpzeXMuZXhpdCgwIGlmIHJlc3VsdHNbJ29rJ10gZWxzZSAzKQo="), StandardCharsets.UTF_8);
        ProcessBuilder builder = new ProcessBuilder(packagedPythonExecutable(stagedRuntimeRoot).toString(), "-c", script);
        Map<String, String> environment = builder.environment();
        environment.put("PYTHONPATH", reticulumPythonPath(stagedRuntimeRoot));
        environment.put("PYTHONNOUSERSITE", "1");
        environment.remove("PYTHONHOME");
        Process process = builder.start();
        boolean finished = process.waitFor(Duration.ofSeconds(90).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        if (!finished)
        {
            process.destroyForcibly();
            throw new IOException("Live extended protocol smoke timed out");
        }
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ImportProbeResult(process.exitValue(), stdout, stderr);
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
