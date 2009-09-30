package jetbrains.buildServer.commandline;

import java.util.HashMap;
import java.util.List;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import jetbrains.buildServer.controllers.BaseController;
import jetbrains.buildServer.serverSide.ProjectManager;
import jetbrains.buildServer.serverSide.SBuildType;
import jetbrains.buildServer.web.openapi.PluginDescriptor;
import jetbrains.buildServer.web.openapi.WebControllerManager;
import org.jetbrains.annotations.NonNls;
import org.springframework.web.servlet.ModelAndView;

/**
 * Controller to handle settings for command line remote runner tool.
 * The related page is available from My Settings and Tools page, tools section.
 */
public class CommandLineController extends BaseController {

  @NonNls private static final String CONTROLLER_PATH = "/commandline.html";
  @NonNls private static final String MY_JSP = "commandlineSettings.jsp";

  private final PluginDescriptor myPluginDescriptor;
  private final WebControllerManager myWebControllerManager;
  private final ProjectManager myProjectManager;

  public CommandLineController(final PluginDescriptor pluginDescriptor,
                               final WebControllerManager webControllerManager,
                               final ProjectManager projectManager) {
    myPluginDescriptor = pluginDescriptor;
    myWebControllerManager = webControllerManager;
    myProjectManager = projectManager;
  }

  public void register() {
    myWebControllerManager.registerController(CONTROLLER_PATH, this);
  }

  @Override
  protected ModelAndView doHandle(final HttpServletRequest request, final HttpServletResponse response) throws Exception {
    final HashMap model = new HashMap();
    addBuildTypes(model);
    CommandLineSection.addPathPrefix(model, request, myPluginDescriptor);

    return new ModelAndView(myPluginDescriptor.getPluginResourcesPath(MY_JSP), model);
  }

  private void addBuildTypes(final HashMap model) {
    final List<SBuildType> buildTypes = myProjectManager.getActiveBuildTypes();
    model.put("buildTypes", buildTypes);
  }

}