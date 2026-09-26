package com.hdmapreforged;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/** The wiki's map version as last seen, and when it was checked, kept on disk between sessions. */
@Slf4j
final class KnownVersion
{
    static final String FILE = "map-version.txt";
    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    final String version;
    final long checkedAt;

    KnownVersion(String version, long checkedAt)
    {
        this.version = version;
        this.checkedAt = checkedAt;
    }

    /** Whether the wiki should be asked again. A check time in the future (a changed clock) counts as due. */
    boolean due(HdMapReforgedConfig.UpdateCheck check, long now)
    {
        if (check.days < 0)
        {
            return false;
        }
        return check.days == 0 || now < checkedAt || now - checkedAt >= check.days * DAY_MS;
    }

    /** The stored version, or null when missing or unusable. */
    static KnownVersion read(File file)
    {
        if (!file.isFile())
        {
            return null;
        }
        try
        {
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            if (lines.size() < 2 || !WikiClient.isSafeVersion(lines.get(0).trim()))
            {
                return null;
            }
            return new KnownVersion(lines.get(0).trim(), Long.parseLong(lines.get(1).trim()));
        }
        catch (IOException | NumberFormatException e)
        {
            log.debug("Ignoring unreadable {}", file, e);
            return null;
        }
    }

    static void write(File file, String version, long checkedAt)
    {
        try
        {
            File parent = file.getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs())
            {
                return;
            }
            File temp = new File(parent, file.getName() + ".part");
            Files.write(temp.toPath(), (version + "\n" + checkedAt + "\n").getBytes(StandardCharsets.UTF_8));
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        catch (IOException e)
        {
            log.debug("Could not save the map version", e);
        }
    }
}
