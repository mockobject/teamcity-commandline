package jetbrains.buildServer.commandline;

import java.util.ArrayList;
import java.util.List;
import jetbrains.buildServer.BaseWebTestCase;
import jetbrains.buildServer.controllers.MockRequest;
import jetbrains.buildServer.controllers.MockResponse;
import jetbrains.buildServer.util.XmlUtil;
import jetbrains.buildServer.vcs.VcsClientMapping;
import org.jdom.Element;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;


@Test
public class GetBuildTypeMappingActionTest extends BaseWebTestCase {
  private List<VcsClientMapping> myPathPrefixes;
  private GetBuildTypeMappingAction myAction;


  public void should_process_empty_request() throws Exception {

    assertFalse(myAction.canProcess(new MockRequest()));
    assertTrue(myAction.canProcess(new MockRequest("mappingFor", "bt11")));

    final Element response = runActionForBuildType();

    assertEquals("<response />", XmlUtil.to_s(response));
  }

  private Element runActionForBuildType() {
    final Element response = new Element("response");
    myAction.process(new MockRequest("mappingFor", myBuildType.getExternalId()), new MockResponse(), response);
    return response;
  }

  public void should_process_request_with_data() throws Exception {
    myFixture.addVcsRoot("mock", "");
    myPathPrefixes.add(new VcsClientMapping("rusps-app01:1666:////depot/src/", ""));

    final Element response = runActionForBuildType();

    assertEquals(XmlUtil.to_s(XmlUtil.from_s(
      "<response>" +
      "  <mapping>" +
      "    <map from=\".\" to=\"mock://rusps-app01:1666:////depot/src\" comment=\"mock\" />" +
      "  </mapping>" +
      "</response>"))
    , XmlUtil.to_s(response));

  }

  @Override
  @BeforeMethod
  protected void setUp() throws Exception {
    super.setUp();
    myAction = new GetBuildTypeMappingAction(myServer.getProjectManager(), myServer.getVcsManager(), null);
    myPathPrefixes = new ArrayList<VcsClientMapping>();
    PathPrefixesSupport.registerIncludeRuleVcsMappingSupport(myPathPrefixes, myServer.getVcsManager());
  }
}
