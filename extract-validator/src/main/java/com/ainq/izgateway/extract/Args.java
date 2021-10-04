package com.ainq.izgateway.extract;

import java.util.Map;
import java.util.TreeMap;

public class Args {

    /** Map of options to argument help text */
    private static Map<String, String> helpText = new TreeMap<>((s,t) -> s.compareToIgnoreCase(t) );

    /**
     * Initializes help text for an option, and checks to see if arg matches the option.
     * @param arg       The argument to check.
     * @param option    The option to check for
     * @param help      The help text for the option.
     * @param args      Format arguments for the help text.
     * @return  true if the option was found.
     */
    public static boolean hasArgument(String arg, String option, String help, Object ... args) {

        if (!helpText.containsKey(option)) {
            helpText.put(option, String.format(help, args));
        }
        String opt = option.split("[\\Q[<{(] \\E]")[0];
        return arg.startsWith(opt);
    }

    public static void help() {
        System.out.printf("%s [options] file ... %n%n", Validator.class.getCanonicalName());
        System.out.println("Validate or convert inputs (or both) and generate a report\n\nOptions:\n");
        for (Map.Entry<String, String> entry: helpText.entrySet()) {
            System.out.printf("%s\t%s\n", entry.getKey(), entry.getValue());
        }
        System.out.println("\nfile ...\tOne or more files to validate or convert.\n");
    }

}
