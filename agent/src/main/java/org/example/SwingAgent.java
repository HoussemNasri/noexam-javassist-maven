package org.example;

import javassist.*;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

public class SwingAgent {
    static ClassPool pool;

    // marks already visited methods in one thread. Used for thread violation checking
    // to not report same violation multiple times when other methods are called from
    // violating method
    static ConcurrentHashMap<Thread, Integer> threadMarks = new ConcurrentHashMap<>();

    static ProblemListener problemListener;
    static boolean instrumented = false;

    // problems are stored in this vector before listener is added to the agent
    static List<Problem> futureProblems = Collections.synchronizedList(new ArrayList<>());

    // keeps all stack traces o
    static WeakHashMap<Component, StackTraceElement[]> componentAddImplStackTraces = new WeakHashMap<Component, StackTraceElement[]>();

    //
    static boolean monitorEDTViolations;

    public static boolean isMonitorEDTViolations() {
        return monitorEDTViolations;
    }

    public static void premain(String agentArguments, Instrumentation instrumentation) {
        System.out.println("Hello from Agent!");
        instrumentation.addTransformer(new Transformer());
        pool = ClassPool.getDefault();
        pool.importPackage("org.example");
        // Log.instrumentation.info("Instrumentation agent of Swing Explorer activated");
        instrumented = true;
    }

    public static boolean isInstrumented() {
        return instrumented;
    }

    public static void setProblemListener(ProblemListener _violationHandler) {
        problemListener = _violationHandler;
        if(problemListener == null) {
            return;
        }

        // notifying listener about all previousely accured problems
        for(Problem problem : futureProblems) {
            problemListener.problemOccured(problem);
        }
        futureProblems.clear();
    }

    private static void notifyProblemListener() {
        // obtain stack information
        StackTraceElement[] trace = Thread.currentThread().getStackTrace();
        final StackTraceElement[] newTrace = new StackTraceElement[trace.length - 3];
        System.arraycopy(trace, 3, newTrace,  0, newTrace.length);
        final String threadName = Thread.currentThread().getName();

        // notify
        String description = MessageFormat.format("The {1}.{2} method called from {0} thread", threadName, newTrace[0].getClassName(), newTrace[0].getMethodName());

        Problem problem = new Problem(description, newTrace);
        if(problemListener != null) {
            problemListener.problemOccured(problem);
        } else {
            if(monitorEDTViolations) {
                futureProblems.add(problem);
            }
        }
    }

    /** performs checking if we are in the EDT */
    public static void checkEDT() {
        if(isNotEventDispatchThread()) {
            // if we are not in EDT then memorise this thread
            // so that eliminate subsequent call checkings
            Integer entries = (Integer)threadMarks.get(Thread.currentThread());
            if(entries == null) {
                entries = 1;
                notifyProblemListener();
            }
            threadMarks.put(Thread.currentThread(), entries + 1);
        }
    }

    private static boolean isNotEventDispatchThread() {
        // just simple !javax.swing.SwingUtilities.isEventDispatchThread()
        // is ot enough because in case we change event queue
        // the event thread is changed and the single remaining sign
        // is the thread name started by AWT-EventQueue

        String name = Thread.currentThread().getName();
        if(name != null && name.startsWith("AWT-EventQueue")) {
            return false;
        } else {
            return true;
        }
    }

    public static void threadSafeCheckEDT() {
        if(!SwingUtilities.isEventDispatchThread()) {
            // if we are not in EDT then memorise this thread
            // so that eliminate subsequent call checkings
            Integer entries = threadMarks.get(Thread.currentThread());
            if(entries == null) {
                entries = 1;
                // in difference with checkEDT() we don't
                // notify listener because method is considered thread safe
                // and all internal calls to non thread safe methods are legal
            }
            threadMarks.put(Thread.currentThread(), entries + 1);
        }
    }


    /** Removes thread mark for this call */
    public static void finalizeCheckEDT() {
        if (!SwingUtilities.isEventDispatchThread()) {
            Integer entries = threadMarks.get(Thread.currentThread());
            if (entries != null) {
                entries = entries - 1;
                if (entries == 1) {
                    threadMarks.remove(Thread.currentThread());
                } else {
                    threadMarks.put(Thread.currentThread(), entries);
                }
            }
        }
    }

    public static void processContainer_addImpl(Component component) {
        if (component instanceof JButton) {
            JButton button = (JButton) component;
            button.addActionListener(e -> {
                System.out.printf("Button '%s' clicked from Agent!%n", ((JButton) component).getText());
            });

            System.out.println("Inserted a new button: " + ((JButton) component).getText());
        }

        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();

        // remove 2 last elements from stack trace
        StackTraceElement[] newStackTrace = new StackTraceElement[stackTrace.length - 2];
        System.arraycopy(stackTrace, 2, newStackTrace, 0, newStackTrace.length);

        componentAddImplStackTraces.put(component, newStackTrace);
    }

    public static StackTraceElement[] getAddImplStackTrace(Component component) {
        return componentAddImplStackTraces.get(component);
    }

    static class Transformer implements ClassFileTransformer {

        boolean first = true;

        @Override
        public byte[] transform(ClassLoader loader,
                                String className,
                                Class<?> redefiningClass,
                                ProtectionDomain domain, byte[] bytes) throws IllegalClassFormatException {

            className = className.replace("/", ".");

            // instrumenting java.awt.Container.addImpl
            try {
                if(className.equals("java.awt.Container")) {
                    System.out.printf("[SwingAgent] Tansforming %s%n", className);
                    CtClass ctClass = pool.get(className);
                    CtMethod m = ctClass.getDeclaredMethod("addImpl");
                    m.insertBefore("{org.example.SwingAgent.processContainer_addImpl($1);}");
                    return ctClass.toBytecode();
                }
            } catch(Exception ex) {
                error("Error instrumenting class: " + className);
                if(first) {
                    error(ex);
                    first = false;
                }
            }

            // exclude javassist classes non-swing classes
            if(!className.startsWith("javax.swing") ||className.startsWith("javassist")) {
                return bytes;
            }

            try {
                CtClass ctClass = pool.get(className);

                if(extendsJComponent(ctClass)) {

                    // insert EDT check into constructor
                    CtConstructor[] ctors = ctClass.getDeclaredConstructors();
                    for(CtConstructor constr : ctors) {
                        try {
                            constr.insertBefore("{org.example.SwingAgent.checkEDT();}");
                        } catch(Exception ex) {
                            if(first) {
                                error(ex);
                                first = false;
                            }
                            error("Error instrumenting constructor: " + ctClass.getName() + " " + constr.getName() + constr.getSignature());
                        }
                    }

                    // insert EDT check into non-thread safe methods
                    CtMethod[] methods = ctClass.getDeclaredMethods();
                    for(CtMethod m : methods) {
                        try {
                            if(isThreadSafeMethod(m)) {
                                m.insertBefore("{org.example.SwingAgent.threadSafeCheckEDT();}");
                            } else {
                                m.insertBefore("{org.example.SwingAgent.checkEDT();}");
                            }
                            m.insertAfter("{org.example.SwingAgent.finalizeCheckEDT();}", true);
                        } catch(Exception ex) {
                            error("Error instrumenting method: " + ctClass.getName() + " " + m.getName() + m.getSignature());
                            if(first) {
                                error(ex);
                                first = false;
                            }
                        }
                    }
                    debug("Instrumented: " + ctClass.getName());
                    return ctClass.toBytecode();
                } else {
                    debug("NOT instrumented: " + ctClass.getName());
                }
            } catch (Exception e) {
                error("Error instrumenting class: " + className);
                if(first) {
                    error(e);
                    first = false;
                }
            }
            return bytes;
        }
    }

    // determines if class is JComponent or derived
    static boolean extendsJComponent(CtClass ctClass) throws Exception{
        if(ctClass == null) {
            return false;
        }
        if("javax.swing.JComponent".equals(ctClass.getName())) {
            return true;
        }
        try {
            return extendsJComponent(ctClass.getSuperclass());
        } catch (NotFoundException e) {
            throw new Exception(e);
        }
    }

    // determines if it is Swing's thread-safe method
    static boolean isThreadSafeMethod(CtMethod m) {
        String strMethod = m.getName() + m.getSignature();
        String name = m.getName();

        return
                strMethod.equals("repaint()V") ||
                        strMethod.equals("repaint(JIIII)V") ||
                        strMethod.equals("repaint(Ljava/awt/Rectangle;)V") ||
                        strMethod.equals("repaint(IIII)V") ||
                        strMethod.equals("revalidate()V") ||
                        //strMethod.equals("invalidate()V") || /* removed by Alex's request */
                        strMethod.equals("imageUpdate(Ljava/awt/Image;IIIII)Z") ||
                        strMethod.equals("getListeners(Ljava/lang/Class;)[Ljava/util/EventListener;") ||
                        name.startsWith("add") && name.endsWith("Listener") ||
                        name.startsWith("remove") && name.endsWith("Listener");
    }

    static void debug(String string) {
//		System.out.println("[instrumentation][debug] " + string);
    }

    static void error(String string) {
//		System.out.println("[instrumentation][error] " + string);
    }

    static void error(Exception ex) {
//		System.out.print("[instrumentation][error] ");
//		ex.printStackTrace(System.out);
    }

//    public static int buttonCount = 0;
//
//    public static void premain(String agentArgs, Instrumentation inst) {
//    	System.out.println("Hello this is the agent ");
//        try {
//            ClassPool cp = ClassPool.getDefault();
//            CtClass cc = cp.get("com.example.MainClass");
//            CtBehavior[] methods = cc.getDeclaredMethods();
//
//            for (CtBehavior method : methods) {
//                if (method.getName().equals("main")) {
//                    CtClass panelClass = cp.get("javax.swing.JPanel");
//                    CtClass buttonClass = cp.get("javax.swing.JButton");
//                    // CtClass addMethod = cp.getDeclaredMethod("add", panelClass.getDeclaredClasses()[0]);
//
//                    // method.insertBefore("{ if ($0.getClass() == " + buttonClass.getName() + " && $1.getClass() == " + panelClass.getName() + ") {\n org.example.SwingAgent.buttonCount++;\n}}\n", addMethod);
//                }
//            }
//
//            cc.writeFile();
//            cc.detach();
//
//        } catch (Exception e) {
//            e.printStackTrace();
//        }
//    }
}

/*import java.lang.instrument.Instrumentation;
import javassist.*;

public class org.example.SwingAgent {
    public static void premain(String agentArgs, Instrumentation ins) throws CannotCompileException {
        System.out.println("Hello this is the agent");

        ClassPool pool = ClassPool.getDefault();
        CtClass cc = null;
        try {
            cc = pool.get("com.example.MainClass");
            CtMethod mainMethod = cc.getDeclaredMethod("main");
            CtClass[] paramTypes = mainMethod.getParameterTypes();
            CtMethod originalMethod = cc.getDeclaredMethod("main", paramTypes);
            for (CtBehavior method : methods) {
                if (method.getName().equals("main")) {
                    CtClass panelClass = cp.get("javax.swing.JPanel");
                    CtClass buttonClass = cp.get("javax.swing.JButton");
                    CtClass addMethod = cp.getDeclaredMethod("add", panelClass.getDeclaredClasses()[0]);

                    method.insertBefore("{ if ($0.getClass() == " + buttonClass.getName() + " && $1.getClass() == " + panelClass.getName() + ") {\n org.example.SwingAgent.buttonCount++;\n}}\n", addMethod);
                }
            }
            cc.writeFile();        } catch (NotFoundException e) {
            e.printStackTrace();
        } finally {
            if (cc != null) {
                cc.detach();
            }
        }
    }
}*/

/*import java.lang.instrument.Instrumentation;

public class org.example.SwingAgent {
    public static void premain(String agentArgs, Instrumentation instrumentation) {
        instrumentation.addTransformer(new Helper());
    }
}*/
/*
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;

import javax.swing.JButton;

public class org.example.SwingAgent {

    public static void premain(String agentArgs, Instrumentation inst) {
    	System.out.println("This is the agent");
        try {
            Class<?> mainClass = Class.forName("com.example.MainClass");
            Object mainClassInstance = mainClass.newInstance();
            countButtons(mainClassInstance, inst);
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException e) {
            e.printStackTrace();
        }
    }

    private static void countButtons(Object object, Instrumentation inst) {
        Class<?> clazz = object.getClass();
        while (clazz != null) {
            for (Field field : clazz.getDeclaredFields()) {
                field.setAccessible(true);
                if (field.getType() == JButton.class) {
                    try {
                        Object fieldValue = field.get(object);
                        if (fieldValue != null && fieldValue instanceof JButton) {
                            System.out.println("Found a button: " + fieldValue);
                        }
                    } catch (IllegalAccessException e) {
                        e.printStackTrace();
                    }
                }
            }
            clazz = clazz.getSuperclass();
        }
    }
}*/

/*import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;

import javax.swing.JButton;

public class org.example.SwingAgent {

    public static void premain(String agentArgs, Instrumentation inst) {
        try {
            Class<?> mainClass = Class.forName("com.example.MainClass");
            Object mainClassInstance = mainClass.newInstance();
            countButtons(mainClassInstance, inst);
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException e) {
            e.printStackTrace();
        }
    }

    private static void countButtons(Object object, Instrumentation inst) {
        Class<?> clazz = object.getClass();
        while (clazz != null) {
            for (Field field : clazz.getDeclaredFields()) {
                field.setAccessible(true);
                if (field.getType() == JButton.class) {
                    try {
                        Object fieldValue = field.get(object);
                        if (fieldValue != null && fieldValue instanceof JButton) {
                            System.out.println("Found a button: " + fieldValue);
                        }
                    } catch (IllegalAccessException e) {
                        e.printStackTrace();
                    }
                }
            }
            clazz = clazz.getSuperclass();
        }
     // Check for buttons in the object's subcomponents
        if (object instanceof javax.swing.JComponent) {
            javax.swing.JComponent component = (javax.swing.JComponent) object;
            for (javax.swing.JComponent subComponent : component.getComponents()) {
                countButtons(subComponent, inst);
    }
}
    }}*/
/////*******This code helps us to get the methods and it works, the issue is that in com.example.MainClass, the listeners
/////*******are in a static private class, so maybe that's why it doesn't return its methods, lets try something that help
/////*******to get the classes used by com.example.MainClass
/*import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
public class org.example.SwingAgent {

    public static void premain(String agentArgs, Instrumentation inst) {
        try {
            Class<?> mainClass = Class.forName("com.example.MainClass");
            System.out.println("Methods in com.example.MainClass:");
            for (Method method : mainClass.getDeclaredMethods()) {
                System.out.println(method.toGenericString());
            }
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
    }
}
*/
//////****** this code helps to get all classes loaded by com.example.MainClass  during its runtime, literally every class, now lets try
//////******to see if we can get to a method of one of these classes, lets look for a class name first and then try the methods
/*import java.lang.instrument.*;
import java.security.ProtectionDomain;
import java.util.*;

public class org.example.SwingAgent {
    public static void premain(String agentArgs, Instrumentation inst) {
    	System.out.println("Hello thi is the agent");
        inst.addTransformer(new ClassUsageTrackerTransformer());
        startMonitoring();
    }

    private static void startMonitoring() {
    	System.out.println("We entered startMonitoring method");
        Thread monitorThread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(5000); // Adjust the sleep duration as needed
                    ClassUsageTrackerTransformer.printLoadedClasses();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        });
        monitorThread.setDaemon(true);
        monitorThread.start();
    }
}

class ClassUsageTrackerTransformer implements ClassFileTransformer {
    private static Set<String> loadedClasses = new HashSet<>();

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        loadedClasses.add(className);
        return null; // We're not modifying the bytecode, so return null
    }

    public static void printLoadedClasses() {
        System.out.println("Classes loaded during runtime:");
        for (String className : loadedClasses) {
        	if(className.contains("ActionListener") 
             System.out.println(className);
        }
    }
}*/
////***this code works, it looks for the class with "ActionListener" and return it with even its method, but there is 
////***a special command: java -javaagent:org.example.SwingAgent.jar -Dkeyword=ActionListener -jar com.example.MainClass.jar and here what does
////***it return
/*C:\softs\Test\TestAgentMainContextMenu>java -javaagent:org.example.SwingAgent.jar -Dkeyword=ActionListener  -jar com.example.MainClass.jar
Hello this is the agent
We entered startMonitoring method
Button 1 pressed!
Button 2 pressed!
Classes loaded during runtime:
  - Class: java.awt.event.ActionListener
      - Method: actionPerformed
Button 2 pressed!
Classes loaded during runtime:
  - Class: java.awt.event.ActionListener
      - Method: actionPerformed
Button 1 pressed!
Classes loaded during runtime:
  - Class: java.awt.event.ActionListener
      - Method: actionPerformed
Classes loaded during runtime:
  - Class: java.awt.event.ActionListener
      - Method: actionPerformed
*/
/////****well we have some problems with some classes but we'll see them after 
/*import java.lang.instrument.*;
import java.lang.reflect.Method;
import java.security.ProtectionDomain;
import java.util.HashSet;
import java.util.Set;

public class org.example.SwingAgent {

    public static void premain(String agentArgs, Instrumentation inst) {
        System.out.println("Hello this is the agent");
        inst.addTransformer(new ClassUsageTrackerTransformer());
        startMonitoring();
    }

    private static void startMonitoring() {
        System.out.println("We entered startMonitoring method");
        Thread monitorThread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(5000); // Adjust the sleep duration as needed
                    ClassUsageTrackerTransformer.printLoadedClasses(System.getProperty("keyword"));
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        });
        monitorThread.setDaemon(true);
        monitorThread.start();
    }
}

class ClassUsageTrackerTransformer implements ClassFileTransformer {

    private static Set<String> loadedClasses = new HashSet<>();

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                             ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        loadedClasses.add(className.replace("/", ".")); // Store class name with dots
        return null; // We're not modifying the bytecode, so return null
    }

    public static void printLoadedClasses(String keyword) {
        System.out.println("Classes loaded during runtime:");
        for (String className : loadedClasses) {
            if (className!= null&&className.contains(keyword)) {
                try {
                    Class<?> targetClass = Class.forName(className);
                    Method[] methods = targetClass.getDeclaredMethods();
                    System.out.println("  - Class: " + className);
                    for (Method method : methods) {
                        System.out.println("      - Method: " + method.getName());
                    }
                } catch (ClassNotFoundException | NoClassDefFoundError e) {
                    // Handle potential exceptions (class might not be found)
                    System.err.println("  - Error: Class " + className + " not found");
                }
            }
            // Add Javassist logic here to analyze bytecode for methods (if reflection is not suitable)
        }
    }
}
*/


/*import java.lang.instrument.Instrumentation;
import java.lang.instrument.Transform;
import javassist.*;

public class org.example.SwingAgent implements Transform {

    private static final String BUTTON_CLICK_LISTENER_CLASS = "com.example.MainClass$ButtonClickListener";
    private static final String ACTION_PERFORMED_METHOD = "actionPerformed";
    private static final String GET_SOURCE_METHOD = "getSource";

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, 
            ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalStateException {

        if (className.equals(BUTTON_CLICK_LISTENER_CLASS)) {
            try {
                ClassPool pool = ClassPool.getDefault();
                CtClass ctClass = pool.makeClass(new ByteArrayInputStream(classfileBuffer));

                // Identify the actionPerformed method
                CtMethod actionPerfomedMethod = ctClass.getDeclaredMethod(ACTION_PERFORMED_METHOD);

                // Add code to get the button source (the clicked button)
                String getSourceCode = "$0." + GET_SOURCE_METHOD + "();";
                CtClass buttonClass = pool.get("java.awt.event.ActionEvent");
                CtMethod getSourceMethod = buttonClass.getDeclaredMethod(GET_SOURCE_METHOD);
                getSourceMethod.insertBefore(getSourceCode);

                // Add code to cast the source to JButton and get its ID
                String getButtonIdCode = "int buttonId = ((JButton) $1).getID();";
                actionPerfomedMethod.insertBefore(getButtonIdCode);

                // Add code to call a static method in com.example.MainClass to send the button ID
                String sendButtonIdCode = "com.example.MainClass.sendButtonId(buttonId);";
                actionPerfomedMethod.insertBefore(sendButtonIdCode);

                return ctClass.toBytecode();
            } catch (Exception e) {
                e.printStackTrace();
                System.err.println("Error during bytecode modification!");
            }
        }
        return null;
    }

    public static void main(String[] args) {
        // This main method is for agent attachment (not required for your program)
        org.example.SwingAgent agent = new org.example.SwingAgent();
        Instrumentation instrumentation = Instrumentation.getInstrumentation();
        instrumentation.addTransformer(agent);
    }
}*/

/*import javassist.*;

import java.awt.event.ActionListener;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

public class org.example.SwingAgent {

    public static void premain(String agentArgs, Instrumentation instrumentation) {
        instrumentation.addTransformer(new ButtonClickTransformer());
    }

    static class ButtonClickTransformer implements ClassFileTransformer {

    	@Override
    	public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
    	                        ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {
    	    if (className.equals("com.example.MainClass")) {
    	        try {
    	            ClassPool cp = ClassPool.getDefault();
    	            CtClass ctClass = cp.get(className.replace("/", "."));

    	            // Get the ActionListener interface
    	            CtClass actionListenerClass = cp.get("java.awt.event.ActionListener");

    	            // Loop through all methods in the class
    	            for (CtMethod method : ctClass.getDeclaredMethods()) {
    	                // Check if the method implements ActionListener.actionPerformed
    	                if (method.hasAnnotation(ActionListener.class) && method.getSignature().equals("void actionPerformed(java.awt.event.ActionEvent)")) {
    	                    // Insert code to call org.example.SwingAgent.handleButtonClick
    	                    method.insertBefore("org.example.SwingAgent.handleButtonClick($1);");
    	                }
    	            }

    	            return ctClass.toBytecode();
    	        } catch (Exception e) {
    	            e.printStackTrace();
    	        }
    	    }
    	    return null;
    	}
    }

    public static void handleButtonClick(java.awt.event.ActionEvent e) {
        // Send a message to the org.example.SwingAgent indicating which button was clicked
        String buttonName = e.getActionCommand();
        System.out.println("Button clicked: " + buttonName);
    }
}*/


/////////////--------this code is used to detect which button is clicked but it doesn't work as i want (it doesn't return which one is clicked)
/*import javassist.*;
import java.lang.instrument.*;
import java.security.ProtectionDomain;
import java.util.HashMap;
import java.util.Map;
import javax.swing.JButton;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.ByteArrayInputStream;

public class org.example.SwingAgent {
    public static void premain(String agentArgs, Instrumentation inst) {
    	System.out.println("Hello this is the agent");
        inst.addTransformer(new ButtonDetectionTransformer());
        System.out.println("Check if it works");
    }

    static class ButtonDetectionTransformer implements ClassFileTransformer {
        private static final String MAIN_CLASS_NAME = "com.example.MainClass";
        
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {
            if (className.equals(MAIN_CLASS_NAME.replace('.', '/'))) {
                return transformMainClass(classfileBuffer);
            }
            return null;
        }

        private byte[] transformMainClass(byte[] classfileBuffer) {
            ClassPool pool = ClassPool.getDefault();
            CtClass ctClass;
            try {
                ctClass = pool.makeClass(new ByteArrayInputStream(classfileBuffer));
                CtMethod actionPerformedMethod = ctClass.getDeclaredMethod("actionPerformed");
                actionPerformedMethod.insertBefore("org.example.SwingAgent.handleButtonClick($1);");
                return ctClass.toBytecode();
            } catch (Exception e) {
                e.printStackTrace();
            }
            return classfileBuffer;
        }
    }

    public static void handleButtonClick(ActionEvent event) {
        Object source = event.getSource();
        System.out.println("We are in handleButtonClick now");
        if (source instanceof JButton) {
            JButton button = (JButton) source;
            String buttonText = button.getText();
            System.out.println("Button clicked: " + buttonText);
        }
        System.out.println("handlebuttonClick finished for this event");
    }
}*/

/////////////--------this code is used to return the frame's name but it doesn't work
/*import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

import javassist.*;

public class org.example.SwingAgent {
    public static void premain(String agentArgs, Instrumentation instrumentation) {
    	System.out.println("Hello this the agent class");
        instrumentation.addTransformer(new MyClassTransformer());
    }
}

class MyClassTransformer implements ClassFileTransformer {

   

    private boolean createsJFrame(CtMethod method) throws NotFoundException {
        // Check if the method returns a JFrame instance or uses JFrame as a parameter
        String returnType = method.getReturnType().getName();
        boolean hasJFrameParam = false;
        for (CtClass paramType : method.getParameterTypes()) {
            if (paramType.getName().equals("javax.swing.JFrame")) {
                hasJFrameParam = true;
                break;
            }
        }
        return returnType.equals("javax.swing.JFrame") || hasJFrameParam;
    }

	@Override
	public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {
		try {
            ClassPool cp = ClassPool.getDefault();
            CtClass ctClass = cp.get(className.replace("/", "."));

            for (CtMethod method : ctClass.getDeclaredMethods()) {
                // Check if the method creates a JFrame instance
                if (createsJFrame(method)) {
                    // Intercept method invocation
                    method.insertAfter("javax.swing.JFrame frame = ($w) $_;");
                    // Retrieve JFrame title
                    method.insertAfter("String title = frame.getTitle();"
                                        + "System.out.println(\"Title: \" + title);");
                }
            }

            return ctClass.toBytecode();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
	}
}*/
////////////////*************I used this code to look if an event happens but it doesn't work
/*import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import javassist.*;

public class org.example.SwingAgent {
    public static void premain(String agentArgs, Instrumentation instrumentation) {
        instrumentation.addTransformer(new EventTransformer());
    }

    static class EventTransformer implements ClassFileTransformer {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            if (className != null && className.equals("com.example.MainClass")) {
                try {
                    // Create a ClassPool instance which will be used to work with classes
                    ClassPool cp = ClassPool.getDefault();

                    // Create a CtClass instance representing the bytecode of the class being transformed
                    CtClass cc = cp.makeClass(new java.io.ByteArrayInputStream(classfileBuffer));

                    // Look for methods associated with event handling in Swing
                    CtMethod[] methods = cc.getDeclaredMethods();
                    for (CtMethod method : methods) {
                        // Example: Looking for actionPerformed method
                        if (method.getName().equals("actionPerformed")) {
                            // Insert code to detect event
                            method.insertBefore("System.out.println(\"Action event occurred\");");
                        }
                    }

                    // Write modified class bytecode
                    return cc.toBytecode();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            return null;
        }
    }
}
*/
/*import java.lang.instrument.*;
import java.security.ProtectionDomain;

import javassist.*;

public class org.example.SwingAgent {
    public static void premain(String agentArgs, Instrumentation instrumentation) {
    	System.out.println("Hello this is the java agent");
        instrumentation.addTransformer(new SwingComponentTransformer());
    }

    static class SwingComponentTransformer implements ClassFileTransformer {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        	System.out.println("Hello this is the transform method");
            try {
            	System.out.println("we are in try block ");
                if (className != null && className.startsWith("javax/swing")) {
                	System.out.println("we entered the if block ");
                    ClassPool cp = ClassPool.getDefault();
                    CtClass cc = cp.get(className.replace("/", "."));
                    CtConstructor[] constructors = cc.getDeclaredConstructors();
                    for (CtConstructor constructor : constructors) {
                    	System.out.println("we entered the for block");
                        // Intercept constructor calls and log information
                    	constructor.insertBefore("System.out.println(\"Swing component created: " + className + "\");");
                    }
                    return cc.toBytecode();
                }
            } catch (Exception e) {
            	System.out.println("we are in the catch block ");
                e.printStackTrace();
            }
            return null;
        }

		
    }
}*/

///////////*********** I made this class to get the methods called during runtime but it doesn't work as i want
/*import java.lang.instrument.Instrumentation;
import java.security.*;
import java.lang.instrument.*;
import java.util.*;
import javassist.*;
public class org.example.SwingAgent {
  public static void premain(String agentArguments, Instrumentation instrumentation) {  
	  System.out.println("Hello this is the agent");
    instrumentation.addTransformer(new SimpleTransformer());
  } 
  static class SimpleTransformer implements ClassFileTransformer {
	  
	  public SimpleTransformer() {
	    super();
	  }
	 
	  public byte[] transform(ClassLoader loader, String className, Class redefiningClass, ProtectionDomain domain, byte[] bytes) throws IllegalClassFormatException {
	    return transformClass(redefiningClass,bytes);
	  }
	 
	  private byte[] transformClass(Class classToTransform, byte[] b) {
	    ClassPool pool = ClassPool.getDefault();
	    CtClass cl = null;
	    try {
	      cl = pool.makeClass(new java.io.ByteArrayInputStream(b));
	      CtBehavior[] methods = cl.getDeclaredBehaviors();
	      for (int i = 0; i < methods.length; i++) {
	        if (methods[i].isEmpty() == false) {
	          changeMethod(methods[i]);
	        }
	      }
	      b = cl.toBytecode();
	    }
	    catch (Exception e) {
	      e.printStackTrace();
	    }
	    catch (Throwable t) {
	      t.printStackTrace();
	    }
	    finally {
	      if (cl != null) {
	        cl.detach();
	      }
	    }
	    return b;
	  }
	 
	  private void changeMethod(CtBehavior method) throws NotFoundException, CannotCompileException {
	    /*if (method.getName().equals("doIt")) {
	      method.insertBefore("System.out.println(\"started method at \" + new java.util.Date());");
	      method.insertAfter("System.out.println(\"ended method at \" + new java.util.Date());");
	    }*/
	    

	  
	          //MY CODE
	      //!Modifier.isAbstract(method.getModifiers()) -- abstract methods can't be modified. If you get exceptions, then add this to the if statement.
	      //native methods can't be modified.
	      /*if (!Modifier.isNative(method.getModifiers())) {
	          String insertString = "System.out.println(\"started method " + method.getName() + "\");";
	          method.insertBefore(insertString);
	      }
	  }
   }}*/

/////////////********** i am thinking of using java agents and jni together ***********///////////