package saarland.cispa.frontmatter

import org.junit.Test
import saarland.cispa.frontmatter.results.ResultsHandler
import java.nio.file.Paths

class ResultsHandlerTest {

    private var androidJar: String = "/Users/kuznetsov/work/android/sdk/platforms/"

    @Test
    fun testLoadFlatUI() {
        val uiFile = Paths.get("/Users/kuznetsov/work/workspace/backstage_project/backstage-revived/data_v2/uni-app.json")
        val flatModel = ResultsHandler.loadFlatUI(uiFile)
        print("")
    }
}
