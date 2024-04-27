# NeoXam Javassist

## Notes
There may be two scenarios where the transform() method not invoked for all classes:

1. **Class yet not loaded by JVM**: if any class yet not loaded by JVM, then there are chances that Java agent : transform()
will not be invoked for that classes.  Whenever it will be loaded by JVM Java
agent : transform() will be invoked for that class.
 
2. **Class is loaded before initializing ClassFileTransformer**: if you have used any class inside your ClassFileTransformer implementation or any other Agent class,
then that class may be loaded before ClassFileTransformer initialization, 
so Java agent : transform() will not be invoked for that class