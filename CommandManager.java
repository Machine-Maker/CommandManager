/**
 * CommandManager - Manages commands for plugins
 * Copyright (C) Jake Potrebic
 *
 * @author Jake Potrebic
 * @version 0.0.1
 */

package me.machinemaker.commandmanager;

import org.apache.commons.lang.IllegalClassException;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.SimplePluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.spigotmc.SpigotConfig;

import java.lang.annotation.*;
import java.lang.reflect.*;
import java.util.*;

public class CommandManager implements CommandExecutor {

    private JavaPlugin plugin;
    private HashMap<String, BaseCommandClass> singleCommands;
    private HashMap<String, BaseCommandClass> superCommands;

    private CommandMap commandMap;
    private Constructor<PluginCommand> pluginCommandConstructor;

    /**
     * Constructor for a CommandManager instance (YOU SHOULDN"T CREATE MORE THAN ONE)
     * @param plugin instance of the main plugin
     */
    public CommandManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.singleCommands = new HashMap<>();
        this.superCommands = new HashMap<>();

        try {
            if (Bukkit.getPluginManager() instanceof SimplePluginManager) {
                Field f = SimplePluginManager.class.getDeclaredField("commandMap");
                f.setAccessible(true);
                this.commandMap = (CommandMap) f.get(Bukkit.getPluginManager());
                if (this.commandMap == null) throw new IllegalAccessException("commandMap was null!");

                this.pluginCommandConstructor = PluginCommand.class.getDeclaredConstructor(String.class, Plugin.class);
                if (this.pluginCommandConstructor == null) throw new IllegalAccessException("pluginCommandConstructor was null!");
                this.pluginCommandConstructor.setAccessible(true);
            }
        } catch (NoSuchMethodException | NoSuchFieldException | IllegalAccessException e) {
            this.plugin.getLogger().severe("Error accessing the CommandMap for dynamic command registration!");
            e.printStackTrace();
        }
    }

    @Override
    public boolean onCommand(CommandSender cs, Command cmd, String label, String[] args) {
        BaseCommandClass bcc = singleCommands.get(cmd.getName().toLowerCase());
        ClassInfo cmdInfo = null;
        String subCommand = "this should never be used";
        if (bcc == null) { //Must be a super command
            bcc = superCommands.get(cmd.getName()); //bcc should NEVER be none
            if (args.length > 0 && bcc.commands.get(args[0].toLowerCase()) == null) {
                cs.sendMessage(underline(args[0]) + " is not a valid sub-command for " + underline("/" + label)); //TODO: Configurable
                return true;
            }
            else if (args.length > 0 && !args[0].equalsIgnoreCase("help"))
                cmdInfo = bcc.commands.get(args[0].toLowerCase());
            List<String> newArgs = Arrays.asList(args);
            subCommand = newArgs.get(0);
            newArgs = newArgs.subList(1, newArgs.size());
            args = newArgs.toArray(new String[newArgs.size()]);
        }
        else cmdInfo = bcc.commands.get(cmd.getName().toLowerCase());
        ClassInfo classInfo = bcc.info; // Class-wide options

        if (cmdInfo == null) // Must be "help" or NONE
            cmdInfo = classInfo;

        if (cmdInfo instanceof CommandInfo && !((CommandInfo) cmdInfo).ignoreCase) {
            if (!subCommand.equals(((CommandInfo) cmdInfo).name)) {
                cs.sendMessage(underline(subCommand) + " is not a valid sub-command for " + underline("/" + label)); //TODO: Configurable
                return true;
            }
        }

        switch (canExecute(cs, classInfo, cmdInfo)) {
            case NO_PERMS:
                cs.sendMessage("No perms!"); //TODO: Configurable
                return true;
            case NOT_PLAYER:
                cs.sendMessage("This command must be run by a player!"); //TODO: Configurable
                return true;
            case NOT_CONSOLE:
                cs.sendMessage("This command must be run by the console!"); //TODO: Configurable
                return true;
            case NONE: break;
        }

        if (!(cmdInfo instanceof CommandInfo) && cmdInfo instanceof SuperClassInfo) {
            cs.sendMessage("super command"); //TODO: implement help
            return true;
        }
        else if (!(cmdInfo instanceof CommandInfo))
            throw new IllegalStateException("cmdInfo was't an instance of SuperClassInfo or CommandInfo");

        CommandInfo commandInfo = (CommandInfo) cmdInfo;
        ArgsContainer a;
        if (commandInfo.args.length > 0) {
            ArgumentInfo[] cmdArgs = commandInfo.args;
            if (cmdArgs.length != args.length) { //TODO: Add support for single multi-space argument AND Optional arguments
                cs.sendMessage("Use format: " + commandInfo.usageString);
                return true;
            }
            List<Object> values = new ArrayList<>();
            for (int i = 0; i < cmdArgs.length; i++) {
                try {
                    switch (cmdArgs[i].type) {
                        case STRING:
                            if (args[i].length() < cmdArgs[i].minStrLen || args[i].length() > cmdArgs[i].maxStrLen)
                                throw new IllegalArgumentException("Bad string length");
                            values.add(args[i]);
                            break;
                        case INTEGER:
                            int num = Integer.parseInt(args[i]);
                            if (num < cmdArgs[i].minInt || num > cmdArgs[i].maxInt)
                                throw new IllegalArgumentException("Bad int size");
                            values.add(Integer.parseInt(args[i]));
                            break;
                        case DOUBLE:
                            double d = Double.parseDouble(args[i]);
                            if (d < cmdArgs[i].minDouble || d > cmdArgs[i].maxDouble)
                                throw new IllegalArgumentException("Bad double size");
                            values.add(Double.parseDouble(args[i]));
                            break;
                        case PLAYER:
                            Player p = Bukkit.getPlayer(args[i]);
                            if (p == null) throw new IllegalArgumentException("Not player name");
                            values.add(p);
                            break;
                    }
                } catch (IllegalArgumentException e) {
                    cs.sendMessage(cmdArgs[i].type.getInvalidMsg(i, cmdArgs[i], !(e instanceof NumberFormatException)));
                    return true;
                }
            }
            a = new ArgsContainer(Arrays.asList(cmdArgs), values);
        }
        else a = new ArgsContainer();

        Class type = commandInfo.type.c;
        if (type == CommandSender.class) type = classInfo.type.c;
        try {
            Method m = bcc.getClass().getMethod(commandInfo.methodName, type, ArgsContainer.class);
            m.invoke(bcc, commandInfo.type.c.cast(cs), a);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) { // This shouldn't happen b/c of checks in addSingleCommand()
            plugin.getLogger().severe("Something went wrong with " + commandInfo.name);
            plugin.getLogger().severe("Its probably an inconsistency with the UserType and the arguments in the function " + bcc.getClass().getName() + "::" + commandInfo.methodName);
            e.printStackTrace();
        }
        return true;
    }

    /**
     * Initializes a class with commands to be handled by CommandManager
     * @param instance An instance of the class
     * @throws IllegalClassException if the class does not contain any methods with CommandManager annotations
     */
    public void addClass(BaseCommandClass instance) throws IllegalClassException {
        boolean done;
        if (instance.getClass().getAnnotationsByType(CommandSetup.class).length == 1) { // Super/Sub Commands
            SuperClassInfo classInfo;
            classInfo = new SuperClassInfo(instance.getClass());
            instance.init(classInfo, true);
            registerCommand(classInfo);
            this.superCommands.put(classInfo.name.toLowerCase(), instance); // Must be after registerCommand() because that checks for duplicates
            addMethods(instance, true);
            done = true;
        }
        else { // Individual Commands
            ClassInfo classInfo = new ClassInfo(instance.getClass());
            instance.init(classInfo, false);
            done = addMethods(instance, false);
        }
        if (!done) throw new IllegalClassException("This class doesn't contain any methods that have CommandManager annotations!");
    }

    private boolean addMethods(BaseCommandClass instance, boolean isSubCmd) {
        boolean done = false;
        for (Method m : instance.getClass().getMethods()) {
            if (m.getAnnotationsByType(CommandSetup.class).length == 1) {
                if (m.getParameters().length != 2)
                    throw new IllegalStateException(instance.getClass().getName() + "::" + m.getName() + " has more than 2 parameters! It must have exactly 2");
                else if (m.getParameters()[1].getType() != ArgsContainer.class)
                    throw new IllegalStateException(instance.getClass().getName() + "::" + m.getName() + " must have a Arguments type as its second parameter!");

                CommandInfo cmdInfo = new CommandInfo(m);
                Class type = m.getParameters()[0].getType();
//                if (type != cmdInfo.type.c) {
//                    if (cmdInfo.type.c == CommandSender.class && type != instance.info.type.c) {
//
//                    }
//                }
                if (type != cmdInfo.type.c && (cmdInfo.type.c == CommandSender.class && type != instance.info.type.c)) {
                    System.out.println(m.getParameters()[0].getType() != cmdInfo.type.c);
                    System.out.println(cmdInfo.type.c == CommandSender.class && m.getParameters()[0].getType() != instance.info.type.c);
                    throw new IllegalStateException(instance.getClass().getName() + "::" + m.getName() + " has an incorrect first parameter! It does not match the UserType in the annotation!");
                }

                instance.addCommand(cmdInfo);
                if (!isSubCmd) {
                    registerCommand(cmdInfo);
                    this.singleCommands.put(cmdInfo.name.toLowerCase(), instance);
                }
                done = true;
            }
        }
        return done;
    }

    private void registerCommand(SuperClassInfo info) {
        Set<String> cmdNames = singleCommands.keySet();
        cmdNames.addAll(superCommands.keySet());
        if (cmdNames.contains(info.name.toLowerCase()))
            throw new IllegalStateException("Cannot have two commands with the same name (case-insensitive)!");
        PluginCommand command = null;
        try {
            command = this.pluginCommandConstructor.newInstance(info.name, plugin);
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) { e.printStackTrace(); }
        command.setAliases(Arrays.asList(info.aliases));
        command.setDescription(info.description);
        this.commandMap.register(plugin.getName(), command);
        command.setExecutor(this);
    }

    /* CommandInfo -> SuperClassInfo -> ClassInfo */
    private class ClassInfo {
        UserType type;
        String[] permissions;
        PermType permType;

        private ClassInfo(AnnotatedElement t) {
            this.type = loadAnnotations(t, User.class, "value");
            this.permissions = loadAnnotations(t, Permissions.class, "value");
            this.permType = loadAnnotations(t, Permissions.class, "type");
        }
    }
    private class SuperClassInfo extends ClassInfo {
        String name;
        String description;
        String[] aliases;

        private SuperClassInfo(AnnotatedElement t) {
            super(t);
            CommandSetup cmdInfo = t.getAnnotation(CommandSetup.class);
            this.name = cmdInfo.name();
            this.type = cmdInfo.type();
            this.description = loadAnnotations(t, Description.class, "value");
            this.aliases = loadAnnotations(t, Aliases.class, "value");
        }
    }
    private class CommandInfo extends SuperClassInfo {
        boolean ignoreCase;
        ArgumentInfo[] args;
        String methodName;
        String usageString; //TODO: Add annotation to customize

        private CommandInfo(Method m) {
            super(m);
            this.ignoreCase = loadAnnotations(m, CommandSetup.class, "ignoreCase");
            Argument[] a = new Argument[] { };
            if (m.getAnnotationsByType(Argument.class).length > 0)
                a = m.getAnnotationsByType(Argument.class);
            this.usageString = "/" + this.name;
            List<ArgumentInfo> l = new ArrayList<>();
            List<String> argNames = new ArrayList<>();
            for (int i = 0; i < a.length; i++) {
                if (!argNames.contains(a[i].name())) {
                    argNames.add(a[i].name());
                    l.add(new ArgumentInfo(a[i], i));
                    this.usageString += " <" + a[i].name() + ">";
                }
                else throw new IllegalStateException("Cannot have two arguments with the same name!");
            }
            this.args = l.toArray(new ArgumentInfo[l.size()]);
            this.methodName = m.getName();
        }
    }

    private class ArgumentInfo {
        String name;
        ArgType type;
        int minInt;
        int maxInt;
        double minDouble;
        double maxDouble;
        int minStrLen;
        int maxStrLen;
        int position;

        private ArgumentInfo(Argument a, int position) {
            this.name = a.name();
            this.type = a.type();
            this.minInt = a.minInt();
            this.maxInt = a.maxInt();
            this.minDouble = a.minDouble();
            this.maxDouble = a.maxDouble();
            this.minStrLen = a.minStrLen();
            this.maxStrLen = a.maxStrLen();
            this.position = position;
        }
    }

    public class ArgsContainer {
        private List<ArgumentInfo> args;
        private List<Object> values;

        private ArgsContainer(List<ArgumentInfo> args, List<Object> values) {
            this.args = args;
            this.values = values;
        }

        private ArgsContainer() {
            this.args = new ArrayList<>();
            this.values = new ArrayList<>();
        }

        private ArgumentInfo getArg(String name) {
            for (ArgumentInfo argumentInfo : args)
                if (argumentInfo.name.equals(name))
                    return argumentInfo;
            return null;
        }

        private ArgumentInfo getArg(int position) {
            for (ArgumentInfo argumentInfo : args)
                if (argumentInfo.position == position)
                    return argumentInfo;
            return null;
        }

        /**
         * Returns the value with the argument name in Object form
         * @param name name of the argument
         * @return value of the argument without a type
         */
        public Object getObj(String name) {
            for (int i = 0; i < args.size(); i++)
                if (args.get(i).name.equals(name))
                    return values.get(i);
            return null;
        }

        /**
         * Gets the argument with a specified name and casts it to a specified type
         * @param name name of the argument (from the @Arg annotation)
         * @param c type of the argument
         * @return value cast to the specified type
         */
        public <T> T get(String name, Class<T> c) {
            ArgumentInfo argumentInfo = this.getArg(name);
            if (argumentInfo == null) throw new IllegalStateException(name + " is not a valid argument name!");
            if (c != argumentInfo.type.c) throw new IllegalStateException("get type does not match argument type!");
            return c.cast(this.getObj(name));
        }

        /**
         * Gets the argument at an index and casts it to a specified type
         * @param position index of the argument (starts at 0)
         * @param c type of the argument (e.g. Integer.class, Player.class)
         * @return value cast to the specified type
         */
        public <T> T get(int position, Class<T> c) {
            ArgumentInfo argumentInfo = this.getArg(position);
            if (argumentInfo == null) throw new IllegalStateException(position + " is not a valid position value!");
            if (c != argumentInfo.type.c) throw new IllegalStateException("get type does not match argument type!");
            return c.cast(this.getObj(argumentInfo.name));
        }
    }

    public static class BaseCommandClass {
        Map<String, CommandInfo> commands;
        boolean isSuper = false;
        ClassInfo info;

        public BaseCommandClass() {
            commands = new HashMap<>();
        }

        private void addCommand(CommandInfo command) {
            if (isSuper) {
                command.usageString = "/" + ((SuperClassInfo) this.info).name + " " + command.usageString.substring(1);
                if (this.commands.containsKey(command.name)) throw new IllegalStateException("Cannot have two super-commands with the same alias");
                for (String s : command.aliases)
                    if (this.commands.containsKey(s))
                        throw new IllegalStateException("Cannot have two sub-commands with the same alias");
            }
            commands.put(command.name.toLowerCase(), command);
            for (String a : command.aliases)
                commands.put(a.toLowerCase(), command);
        }

        private void init(ClassInfo info, boolean isSuper) {
            this.info = info;
            this.isSuper = isSuper;
        }
    }

    /** Annotations */
    /* Method/Class */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.METHOD, ElementType.TYPE})
    public @interface CommandSetup {
        String name();
        UserType type() default UserType.ALL;
        boolean ignoreCase() default true;
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.METHOD, ElementType.TYPE})
    public @interface Description { String value() default "No description provided"; }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.METHOD, ElementType.TYPE})
    public @interface Aliases { String[] value() default {}; }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.METHOD, ElementType.TYPE})
    public @interface Permissions {
        String[] value() default {};
        PermType type() default PermType.OR;
    }
    /* Method ONLY */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @Repeatable(ArgumentContainer.class)
    public @interface Argument {
        String name();
        ArgType type() default ArgType.STRING;
        int minInt() default Integer.MIN_VALUE; //TODO: Implement
        int maxInt() default Integer.MAX_VALUE;
        double minDouble() default -Double.MAX_VALUE;
        double maxDouble() default Double.MAX_VALUE;
        int minStrLen() default 0;
        int maxStrLen() default Integer.MAX_VALUE;
    }

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface ArgumentContainer { Argument[] value(); }
    /* Class ONLY */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    public @interface User { UserType value() default UserType.ALL; }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    public @interface SuperCmdOptions{ //TODO: Implement
        /**
         * Shows help when the super command is followed by "help" or nothing
         */
        boolean showHelp() default true;

        /**
         * If showHelp is "true", this will only show commands you have permission for
         */
        boolean filterByPerms() default true;
    }
    //TODO: Add cooldown annotations

    /* Public enums */
    public enum PermType {
        OR,
        AND
    }
    public enum UserType {
        PLAYER(Player.class, CmdIssue.NOT_PLAYER),
        CONSOLE(ConsoleCommandSender.class, CmdIssue.NOT_CONSOLE),
        ALL(CommandSender.class, null);

        final Class<? extends CommandSender> c;
        CmdIssue issue;

        UserType(Class<? extends CommandSender> c, CmdIssue issue) {
            this.c = c;
            this.issue = issue;
        }
    }
    public enum ArgType {
        STRING(String.class, "string", "with a length between"),
        INTEGER(Integer.class, "whole number", "between"),
        DOUBLE(Double.class, "decimal", "between"),
        PLAYER(Player.class, "player name", null);

        final String desc;
        final Class c;
        final String v;

        final String msg = "The %pos% (%name%) argument requires a %type%";
        final String validator = " %v% %min% and %max%";

        ArgType(Class c, String desc, String v) {
            this.c = c;
            this.desc = desc;
            this.v = v;
        }
        private String getInvalidMsg(int position, ArgumentInfo arg, boolean outOfBounds) {
            String s = this.msg.replace("%pos%", ordinal(position+1)).replace("%name%", arg.name).replace("%type%", arg.type.desc);
            if (outOfBounds && this.v != null) {
                s += this.validator.replace("%v%", this.v);
                System.out.println(arg.type);
                if (arg.type == ArgType.INTEGER) s = s.replace("%min%", ""+arg.minInt).replace("%max%", ""+arg.maxInt);
                else if (arg.type == ArgType.DOUBLE) s = s.replace("%min%", ""+arg.minDouble).replace("%max%", ""+arg.maxDouble);
                else if (arg.type == ArgType.STRING) s = s.replace("%min%", ""+arg.minStrLen).replace("%max%", ""+arg.maxStrLen);
            }

            return s + ".";
        }

        private static String ordinal(int i) {
            String[] sufixes = new String[] { "th", "st", "nd", "rd", "th", "th", "th", "th", "th", "th" };
            switch (i % 100) {
                case 11:
                case 12:
                case 13:
                    return i + "th";
                default:
                    return i + sufixes[i % 10];
            }
        }
    }
    /* Private enums */
    private enum CmdIssue {
        NO_PERMS,
        NOT_PLAYER,
        NOT_CONSOLE,
        NONE
    }

    /* Helper functions */
    private CmdIssue canExecute(CommandSender cs, ClassInfo...infos) {
        if (!(cs instanceof ConsoleCommandSender)) {
            boolean[] passedPerm = new boolean[infos.length];
            Arrays.fill(passedPerm, false);
            for (int i = 0; i < infos.length; i++) {
                boolean hasPerm = infos[i].permissions.length == 0; // for PermType.OR
                boolean hasAllPerms = true; // for PermType.AND
                for (String perm : infos[i].permissions) {
                    if (cs.hasPermission(perm)) hasPerm = true;
                    else hasAllPerms = false;
                }
                switch (infos[i].permType) {
                    case OR:
                        passedPerm[i] = hasPerm;
                        break;
                    case AND:
                        passedPerm[i] = hasAllPerms;
                        break;
                }
            }
            for (boolean b : passedPerm) if (!b) return CmdIssue.NO_PERMS;
        }
        for (ClassInfo info : infos)
            if (!info.type.c.isInstance(cs))
                return info.type.issue;
        return CmdIssue.NONE;
    }
    private String underline(String s) {
        return ChatColor.UNDERLINE + s + ChatColor.RESET;
    }
    private <T> T loadAnnotations(AnnotatedElement t, Class<? extends Annotation> c, String value) {
        try {
            if (t.getAnnotation(c) == null)
                return (T) c.getMethod(value).getDefaultValue();
            Method am = t.getAnnotation(c).getClass().getMethod(value);
            return (T) am.invoke(t.getAnnotation(c));
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) { e.printStackTrace(); return null; }
    }

//    /**
//     * Copyright (c) 2015, SpigotMC Pty. Ltd. All rights reserved.
//     *
//     * Redistribution and use in source and binary forms, with or without
//     * modification, are permitted provided that the following conditions are met:
//     *
//     * Redistributions of source code must retain the above copyright notice, this
//     * list of conditions and the following disclaimer.
//     *
//     * Redistributions in binary form must reproduce the above copyright notice,
//     * this list of conditions and the following disclaimer in the documentation
//     * and/or other materials provided with the distribution.
//     *
//     * The name of the author may not be used to endorse or promote products derived
//     * from this software without specific prior written permission.
//     *
//     * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
//     * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
//     * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
//     * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
//     * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
//     * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
//     * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
//     * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
//     * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
//     * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
//     * POSSIBILITY OF SUCH DAMAGE.
//     */
//
//    /** =============== Command =============== **/
//    /**
//     * Defines a plugin command
//     */
//    @Documented
//    @Retention(RetentionPolicy.RUNTIME)
//    @Target(ElementType.TYPE)
//    @Repeatable(Commands.class)
//    public @interface Command {
//        String name();
//        String desc() default "";
//        String[] aliases() default {};
//        String permission() default "";
//        String permissionMessage() default "";
//        String usage() default "";
//    }
//    /**
//     * Use multiple {@link Command} annotations instead
//     */
//    @Documented
//    @Target(ElementType.TYPE)
//    @Retention(RetentionPolicy.RUNTIME)
//    public @interface Commands { Command[] value() default {}; }
//    /** ============= End Command ============= **/
//    /** =============== Dependency =============== **/
//    /** ============= End Dependency ============= **/
//    /** =============== Permission =============== **/
//    /** ============= End Permission ============= **/
//
//    /** =============== Plugin =============== **/
//    /* =============== Author =============== */
//    /**
//     * Use multiple Author annotations instead!
//     */
//    @Documented
//    @Retention(RetentionPolicy.RUNTIME)
//    @Target(ElementType.TYPE)
//    public @interface Authors { Author[] value(); }
//    /**
//     *  Plugin author/authors
//     *  Use multiple annotations for multiple authors
//     */
//    @Documented
//    @Retention(RetentionPolicy.RUNTIME)
//    @Target(ElementType.TYPE)
//    @Repeatable(Authors.class)
//    public @interface Author { String value(); }
//    /* =============== End Author =============== */
//    /**
//     * This annotation specifies the api version of the plugin.
//     * Defaults to {@link ApiVersion.Target#DEFAULT}.
//     * Pre-1.13 plugins do not need to use this annotation.
//     */
//    @Documented
//    @Retention(RetentionPolicy.RUNTIME)
//    @Target( ElementType.TYPE )
//    public @interface ApiVersion {
//        Target value() default Target.DEFAULT;
//        enum Target {
//            DEFAULT( null ),
//            v1_13( "1.13", DEFAULT );
//
//            private final String version;
//            private final Collection<Target> conflictsWith = Sets.newLinkedHashSet();
//
//            Target(String version, Target... conflictsWith) {
//                this.version = version;
//                this.conflictsWith.addAll( Lists.newArrayList( conflictsWith ) );
//            }
//            public String getVersion() { return version; }
//
//            public boolean conflictsWith(Target target) { return this.conflictsWith.contains( target ); }
//        }
//    }
//    /**
//     *  Short plugin description
//     */
//    @Documented
//    @Retention(RetentionPolicy.RUNTIME)
//    @Target(ElementType.TYPE)
//    public @interface Description { String value(); }
//    /**
//     * Optimal load position of the plugin {@link PluginLoadOrder}
//     * Defaults to {@link PluginLoadOrder#POSTWORLD}.
//     */
//    @Documented
//    @Retention(RetentionPolicy.RUNTIME)
//    @Target(ElementType.TYPE)
//    public @interface LoadOrder { PluginLoadOrder value() default PluginLoadOrder.POSTWORLD; }
//    /**
//     *  Prefix for logging
//     */
//    @Documented
//    @Retention(RetentionPolicy.RUNTIME)
//    @Target(ElementType.TYPE)
//    public @interface LogPrefix { String value(); }
//    /**
//     *  Must be placed on the class that extends {@link org.bukkit.plugin.java.JavaPlugin}
//     */
//    @Documented
//    @Retention(RetentionPolicy.RUNTIME)
//    @Target(ElementType.TYPE)
//    public @interface Plugin {
//        String name();
//        String version();
//        String DEFAULT_VERSION = "v0.0";
//    }
//    /**
//     *  Plugin website url
//     */
//    @Documented
//    @Retention(RetentionPolicy.RUNTIME)
//    @Target(ElementType.TYPE)
//    public @interface Website { String value(); }
//    /** =============== End Plugin =============== **/
//
//
//
//    @SupportedAnnotationTypes( "org.bukkit.plugin.java.annotation.*" )
//    @SupportedSourceVersion( SourceVersion.RELEASE_8 )
//    public class PluginAnnotationProcessor extends AbstractProcessor {
//
//        private boolean hasMainBeenFound = false;
//
//        private final DateTimeFormatter dFormat = DateTimeFormatter.ofPattern( "yyyy/MM/dd HH:mm:ss", Locale.ENGLISH );
//
//        @Override
//        public boolean process(Set<? extends TypeElement> annots, RoundEnvironment rEnv) {
//            Element mainPluginElement = null;
//            hasMainBeenFound = false;
//
//            Set<? extends Element> elements = rEnv.getElementsAnnotatedWith( Plugin.class );
//            if ( elements.size() > 1 ) {
//                raiseError( "Found more than one plugin main class" );
//                return false;
//            }
//
//            if ( elements.isEmpty() ) { // don't raise error because we don't know which run this is
//                return false;
//            }
//            if ( hasMainBeenFound ) {
//                raiseError( "The plugin class has already been located, aborting!" );
//                return false;
//            }
//            mainPluginElement = elements.iterator().next();
//            hasMainBeenFound = true;
//
//            TypeElement mainPluginType;
//            if ( mainPluginElement instanceof TypeElement ) {
//                mainPluginType = ( TypeElement ) mainPluginElement;
//            } else {
//                raiseError( "Main plugin class is not a class", mainPluginElement );
//                return false;
//            }
//
//            if ( !( mainPluginType.getEnclosingElement() instanceof PackageElement ) ) {
//                raiseError( "Main plugin class is not a top-level class", mainPluginType );
//                return false;
//            }
//
//            if ( mainPluginType.getModifiers().contains( Modifier.STATIC ) ) {
//                raiseError( "Main plugin class cannot be static nested", mainPluginType );
//                return false;
//            }
//
//            if ( !processingEnv.getTypeUtils().isSubtype( mainPluginType.asType(), fromClass( JavaPlugin.class ) ) ) {
//                raiseError( "Main plugin class is not an subclass of JavaPlugin!", mainPluginType );
//            }
//
//            if ( mainPluginType.getModifiers().contains( Modifier.ABSTRACT ) ) {
//                raiseError( "Main plugin class cannot be abstract", mainPluginType );
//                return false;
//            }
//
//            // check for no-args constructor
//            checkForNoArgsConstructor( mainPluginType );
//
//            Map<String, Object> yml = Maps.newLinkedHashMap(); // linked so we can maintain the same output into file for sanity
//
//            // populate mainName
//            final String mainName = mainPluginType.getQualifiedName().toString();
//            yml.put( "main", mainName ); // always override this so we make sure the main class name is correct
//
//            // populate plugin name
//            processAndPut( yml, "name", mainPluginType, mainName.substring( mainName.lastIndexOf( '.' ) + 1 ), Plugin.class, String.class, "name" );
//
//            // populate version
//            processAndPut( yml, "version", mainPluginType, Plugin.DEFAULT_VERSION, Plugin.class, String.class, "version" );
//
//            // populate plugin description
//            processAndPut( yml, "description", mainPluginType, null, Description.class, String.class );
//
//            // populate plugin load order
//            processAndPut( yml, "load", mainPluginType, null, LoadOrder.class, String.class );
//
//            // authors
//            Author[] authors = mainPluginType.getAnnotationsByType( Author.class );
//            List<String> authorMap = Lists.newArrayList();
//            for ( Author auth : authors ) {
//                authorMap.add( auth.value() );
//            }
//            if ( authorMap.size() > 1 ) {
//                yml.put( "authors", authorMap );
//            } else if ( authorMap.size() == 1 ) {
//                yml.put( "author", authorMap.iterator().next() );
//            }
//
//            // website
//            processAndPut( yml, "website", mainPluginType, null, Website.class, String.class );
//
//            // prefix
//            processAndPut( yml, "prefix", mainPluginType, null, LogPrefix.class, String.class );
//
//            // dependencies
//            Dependency[] dependencies = mainPluginType.getAnnotationsByType( Dependency.class );
//            List<String> hardDependencies = Lists.newArrayList();
//            for ( Dependency dep : dependencies ) {
//                hardDependencies.add( dep.value() );
//            }
//            if ( !hardDependencies.isEmpty() ) yml.put( "depend", hardDependencies );
//
//            // soft-dependencies
//            SoftDependency[] softDependencies = mainPluginType.getAnnotationsByType( SoftDependency.class );
//            String[] softDepArr = new String[ softDependencies.length ];
//            for ( int i = 0; i < softDependencies.length; i++ ) {
//                softDepArr[ i ] = softDependencies[ i ].value();
//            }
//            if ( softDepArr.length > 0 ) yml.put( "softdepend", softDepArr );
//
//            // load-before
//            LoadBefore[] loadBefore = mainPluginType.getAnnotationsByType( LoadBefore.class );
//            String[] loadBeforeArr = new String[ loadBefore.length ];
//            for ( int i = 0; i < loadBefore.length; i++ ) {
//                loadBeforeArr[ i ] = loadBefore[ i ].value();
//            }
//            if ( loadBeforeArr.length > 0 ) yml.put( "loadbefore", loadBeforeArr );
//
//            // commands
//            // Begin processing external command annotations
//            Map<String, Map<String, Object>> commandMap = Maps.newLinkedHashMap();
//            boolean validCommandExecutors = processExternalCommands( rEnv.getElementsAnnotatedWith( Commands.class ), mainPluginType, commandMap );
//            if ( !validCommandExecutors ) {
//                // #processExternalCommand already raised the errors
//                return false;
//            }
//
//            Commands commands = mainPluginType.getAnnotation( Commands.class );
//
//            // Check main class for any command annotations
//            if ( commands != null ) {
//                Map<String, Map<String, Object>> merged = Maps.newLinkedHashMap();
//                merged.putAll( commandMap );
//                merged.putAll( this.processCommands( commands ) );
//                commandMap = merged;
//            }
//
//            yml.put( "commands", commandMap );
//
//            // Permissions
//            Map<String, Map<String, Object>> permissionMetadata = Maps.newLinkedHashMap();
//
//            Set<? extends Element> permissionAnnotations = rEnv.getElementsAnnotatedWith( Command.class );
//            if ( permissionAnnotations.size() > 0 ) {
//                for ( Element element : permissionAnnotations ) {
//                    if ( element.equals( mainPluginElement ) ) {
//                        continue;
//                    }
//                    if ( element.getAnnotation( Permission.class ) != null ) {
//                        Permission permissionAnnotation = element.getAnnotation( Permission.class );
//                        permissionMetadata.put( permissionAnnotation.name(), this.processPermission( permissionAnnotation ) );
//                    }
//                }
//            }
//
//            Permissions permissions = mainPluginType.getAnnotation( Permissions.class );
//            if ( permissions != null ) {
//                Map<String, Map<String, Object>> joined = Maps.newLinkedHashMap();
//                joined.putAll( permissionMetadata );
//                joined.putAll( this.processPermissions( permissions ) );
//                permissionMetadata = joined;
//            }
//
//            // Process Permissions on command executors
//            boolean validPermissions = processExternalPermissions( rEnv.getElementsAnnotatedWith( Permissions.class ), mainPluginType, permissionMetadata );
//            if ( !validPermissions ) {
//                return false;
//            }
//            yml.put( "permissions", permissionMetadata );
//
//            // api-version
//            if ( mainPluginType.getAnnotation( ApiVersion.class ) != null ) {
//                ApiVersion apiVersion = mainPluginType.getAnnotation( ApiVersion.class );
//                if ( apiVersion.value() != ApiVersion.Target.DEFAULT ) {
//                    yml.put( "api-version", apiVersion.value().getVersion() );
//                }
//            }
//
//            try {
//                Yaml yaml = new Yaml();
//                FileObject file = this.processingEnv.getFiler().createResource( StandardLocation.CLASS_OUTPUT, "", "plugin.yml" );
//                try ( Writer w = file.openWriter() ) {
//                    w.append( "# Auto-generated plugin.yml, generated at " )
//                            .append( LocalDateTime.now().format( dFormat ) )
//                            .append( " by " )
//                            .append( this.getClass().getName() )
//                            .append( "\n\n" );
//                    // have to format the yaml explicitly because otherwise it dumps child nodes as maps within braces.
//                    String raw = yaml.dumpAs( yml, Tag.MAP, DumperOptions.FlowStyle.BLOCK );
//                    w.write( raw );
//                    w.flush();
//                    w.close();
//                }
//                // try with resources will close the Writer since it implements Closeable
//            } catch ( IOException e ) {
//                throw new RuntimeException( e );
//            }
//            return true;
//        }
//
//        private boolean processExternalPermissions(Set<? extends Element> commandExecutors, TypeElement mainPluginType, Map<String, Map<String, Object>> yml) {
//            for ( Element element : commandExecutors ) {
//                // Check to see if someone annotated a non-class with this
//                if ( !( element instanceof TypeElement ) ) {
//                    this.raiseError( "Specified Command Executor class is not a class." );
//                    return false;
//                }
//
//                TypeElement typeElement = ( TypeElement ) element;
//                if ( typeElement.equals( mainPluginType ) ) {
//                    continue;
//                }
//
//                // Check to see if annotated class is actuall a command executor
//                TypeMirror mirror = this.processingEnv.getElementUtils().getTypeElement( CommandExecutor.class.getName() ).asType();
//                if ( !( this.processingEnv.getTypeUtils().isAssignable( typeElement.asType(), mirror ) ) ) {
//                    this.raiseError( "Specified Command Executor class is not assignable from CommandExecutor " );
//                    return false;
//                }
//
//                Map<String, Map<String, Object>> newMap = Maps.newLinkedHashMap();
//                Permissions annotation = typeElement.getAnnotation( Permissions.class );
//                if ( annotation != null && annotation.value().length > 0 ) {
//                    newMap.putAll( processPermissions( annotation ) );
//                }
//                yml.putAll( newMap );
//            }
//            return true;
//        }
//
//        private void checkForNoArgsConstructor(TypeElement mainPluginType) {
//            for ( ExecutableElement constructor : ElementFilter.constructorsIn( mainPluginType.getEnclosedElements() ) ) {
//                if ( constructor.getParameters().isEmpty() ) {
//                    return;
//                }
//            }
//            raiseError( "Main plugin class must have a no argument constructor.", mainPluginType );
//        }
//
//        private void raiseError(String message) {
//            this.processingEnv.getMessager().printMessage( Diagnostic.Kind.ERROR, message );
//        }
//
//        private void raiseError(String message, Element element) {
//            this.processingEnv.getMessager().printMessage( Diagnostic.Kind.ERROR, message, element );
//        }
//
//        private TypeMirror fromClass(Class<?> clazz) {
//            return processingEnv.getElementUtils().getTypeElement( clazz.getName() ).asType();
//        }
//
//        private <A extends Annotation, R> R processAndPut(
//                Map<String, Object> map, String name, Element el, R defaultVal, Class<A> annotationType, Class<R> returnType) {
//            return processAndPut( map, name, el, defaultVal, annotationType, returnType, "value" );
//        }
//
//        private <A extends Annotation, R> R processAndPut(
//                Map<String, Object> map, String name, Element el, R defaultVal, Class<A> annotationType, Class<R> returnType, String methodName) {
//            R result = process( el, defaultVal, annotationType, returnType, methodName );
//            if ( result != null )
//                map.put( name, result );
//            return result;
//        }
//
//        private <A extends Annotation, R> R process(Element el, R defaultVal, Class<A> annotationType, Class<R> returnType, String methodName) {
//            R result;
//            A ann = el.getAnnotation( annotationType );
//            if ( ann == null ) result = defaultVal;
//            else {
//                try {
//                    Method value = annotationType.getMethod( methodName );
//                    Object res = value.invoke( ann );
//                    result = ( R ) ( returnType == String.class ? res.toString() : returnType.cast( res ) );
//                } catch ( Exception e ) {
//                    throw new RuntimeException( e ); // shouldn't happen in theory (blame Choco if it does)
//                }
//            }
//            return result;
//        }
//
//        private boolean processExternalCommands(Set<? extends Element> commandExecutors, TypeElement mainPluginType, Map<String, Map<String, Object>> commandMetadata) {
//            for ( Element element : commandExecutors ) {
//                // Check to see if someone annotated a non-class with this
//                if ( !( element instanceof TypeElement ) ) {
//                    this.raiseError( "Specified Command Executor class is not a class." );
//                    return false;
//                }
//
//                TypeElement typeElement = ( TypeElement ) element;
//                if ( typeElement.equals( mainPluginType ) ) {
//                    continue;
//                }
//
//                // Check to see if annotated class is actuall a command executor
//                TypeMirror mirror = this.processingEnv.getElementUtils().getTypeElement( CommandExecutor.class.getName() ).asType();
//                if ( !( this.processingEnv.getTypeUtils().isAssignable( typeElement.asType(), mirror ) ) ) {
//                    this.raiseError( "Specified Command Executor class is not assignable from CommandExecutor " );
//                    return false;
//                }
//
//                Commands annotation = typeElement.getAnnotation( Commands.class );
//                if ( annotation != null && annotation.value().length > 0 ) {
//                    commandMetadata.putAll( this.processCommands( annotation ) );
//                }
//            }
//            return true;
//        }
//
//        /**
//         * Processes a set of commands.
//         *
//         * @param commands The annotation.
//         *
//         * @return The generated command metadata.
//         */
//        protected Map<String, Map<String, Object>> processCommands(Commands commands) {
//            Map<String, Map<String, Object>> commandList = Maps.newLinkedHashMap();
//            for ( Command command : commands.value() ) {
//                commandList.put( command.name(), this.processCommand( command ) );
//            }
//            return commandList;
//        }
//
//        /**
//         * Processes a single command.
//         *
//         * @param commandAnnotation The annotation.
//         *
//         * @return The generated command metadata.
//         */
//        protected Map<String, Object> processCommand(Command commandAnnotation) {
//            Map<String, Object> command = Maps.newLinkedHashMap();
//
//            if ( commandAnnotation.aliases().length == 1 ) {
//                command.put( "aliases", commandAnnotation.aliases()[ 0 ] );
//            } else if ( commandAnnotation.aliases().length > 1 ) {
//                command.put( "aliases", commandAnnotation.aliases() );
//            }
//
//            if ( !commandAnnotation.desc().isEmpty() ) {
//                command.put( "description", commandAnnotation.desc() );
//            }
//            if ( !commandAnnotation.permission().isEmpty() ) {
//                command.put( "permission", commandAnnotation.permission() );
//            }
//            if ( !commandAnnotation.permissionMessage().isEmpty() ) {
//                command.put( "permission-message", commandAnnotation.permissionMessage() );
//            }
//            if ( !commandAnnotation.usage().isEmpty() ) {
//                command.put( "usage", commandAnnotation.usage() );
//            }
//
//            return command;
//        }
//
//        /**
//         * Processes a command.
//         *
//         * @param permissionAnnotation The annotation.
//         *
//         * @return The generated permission metadata.
//         */
//        protected Map<String, Object> processPermission(Permission permissionAnnotation) {
//            Map<String, Object> permission = Maps.newLinkedHashMap();
//
//            if ( !"".equals( permissionAnnotation.desc() ) ) {
//                permission.put( "description", permissionAnnotation.desc() );
//            }
//            if ( PermissionDefault.OP != permissionAnnotation.defaultValue() ) {
//                permission.put( "default", permissionAnnotation.defaultValue().toString().toLowerCase() );
//            }
//
//            if ( permissionAnnotation.children().length > 0 ) {
//                Map<String, Boolean> childrenList = Maps.newLinkedHashMap(); // maintain order
//                for ( ChildPermission childPermission : permissionAnnotation.children() ) {
//                    childrenList.put( childPermission.name(), childPermission.inherit() );
//                }
//                permission.put( "children", childrenList );
//            }
//
//            return permission;
//        }
//
//        /**
//         * Processes a set of permissions.
//         *
//         * @param permissions The annotation.
//         *
//         * @return The generated permission metadata.
//         */
//        protected Map<String, Map<String, Object>> processPermissions(Permissions permissions) {
//            Map<String, Map<String, Object>> permissionList = Maps.newLinkedHashMap();
//            for ( Permission permission : permissions.value() ) {
//                permissionList.put( permission.name(), this.processPermission( permission ) );
//            }
//            return permissionList;
//        }
//    }
}
