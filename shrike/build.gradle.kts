plugins { id("com.ibm.wala.gradle.java") }

eclipse.project.natures("org.eclipse.pde.PluginNature")

dependencies { api(projects.util) }
