from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))
from check_architecture import check, violations


class ArchitectureTest(unittest.TestCase):
    def test_core_forbids_android_json_di_and_fully_qualified_api(self):
        for source in ["import android.content.Context", "import org.json.JSONObject", "import javax.inject.Inject",
                       "val context: android.content.Context? = null", "import androidx.lifecycle.ViewModel"]:
            with self.subTest(source=source):
                self.assertTrue(violations("core/application", source))

    def test_core_forbids_ambient_time_environment_files_threads_and_default_dispatchers(self):
        for source in ["System.currentTimeMillis()", "java.lang.System.getenv(\"TOKEN\")",
                       "import java.lang.System as Host\nHost.nanoTime()", "import java.nio.file.Files as Disk",
                       "Instant.now()", "UUID.randomUUID()", "Thread.sleep(1)", "Dispatchers.IO", "GlobalScope.launch {}",
                       "import kotlin.io.path.Path", "import kotlin.random.Random", "TimeSource.Monotonic.markNow()",
                       "measureTime { work() }", 'Class.forName("android.content.Context")']:
            with self.subTest(source=source):
                self.assertTrue(violations("core/application", source))

    def test_outer_dependencies_and_composition_root_cannot_leak_inward(self):
        for module, source in [("core/application", "import dev.usix.companion.protocol.LegacyCodec"),
                               ("core/domain", "import dev.usix.companion.application.DeviceQuery"),
                               ("adapters/android", "import dev.usix.companion.adapters.persistence.BridgeCredentialStore"),
                               ("feature/control", "import dev.usix.companion.adapters.android.AndroidAppLauncher"),
                               ("protocol", "import dev.usix.companion.domain.PackageId"),
                               ("core/application", "import dev.usix.companion.CompanionModule")]:
            with self.subTest(module=module):
                self.assertTrue(violations(module, source))

    def test_comments_strings_and_injected_primitives_are_allowed(self):
        source = '''// System.nanoTime() is forbidden.
        import dev.usix.companion.domain.PackageId
        import kotlinx.coroutines.sync.Mutex
        val example = "android.content.Context"
        val other = """System.getenv("PATH")"""
        '''
        self.assertEqual([], violations("core/application", source))

    def test_executable_string_templates_cannot_hide_ambient_apis(self):
        for source in ['val label = "${System.nanoTime()}"',
                       'val label = "${System.getenv("TOKEN")}"',
                       'val label = """${System.currentTimeMillis()}"""',
                       'val label = "${listOf(1).map { System.nanoTime() }}"']:
            with self.subTest(source=source):
                self.assertTrue(violations("core/application", source))
        self.assertEqual([], violations("core/application", r'val text = "\${System.nanoTime()}"'))
        self.assertEqual([], violations("core/application", '// ${System.nanoTime()}\nval text = "literal"'))

    def test_checker_reports_path_and_application_reflection(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "core/domain/src/main/kotlin/Bad.kt"
            source.parent.mkdir(parents=True)
            source.write_text("import android.content.Context")
            test = root / "core/application/src/test/kotlin/BadTest.kt"
            test.parent.mkdir(parents=True)
            test.write_text('BridgeServer::class.java.getDeclaredMethod("route")')
            result = check(root)
            self.assertTrue(any("Bad.kt" in item for item in result))
            self.assertTrue(any("BadTest.kt" in item for item in result))


if __name__ == "__main__":
    unittest.main()
