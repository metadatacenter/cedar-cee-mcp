package org.metadatacenter.cedar.cee;

import java.io.IOException;
import java.util.Locale;

/**
 * Opens a URL in the user's default browser. Best-effort: every tool result includes the URL
 * regardless, so a failed auto-open just means the user clicks the link instead. Tests inject a
 * no-op subclass rather than opening anything.
 */
class BrowserOpener
{
  /** @return true if a browser-open command was launched. */
  boolean open(String url)
  {
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    String[] command;
    if (os.contains("mac"))
      command = new String[] {"open", url};
    else if (os.contains("win"))
      command = new String[] {"rundll32", "url.dll,FileProtocolHandler", url};
    else
      command = new String[] {"xdg-open", url};

    try {
      new ProcessBuilder(command).start();
      return true;
    } catch (IOException e) {
      return false;
    }
  }
}
