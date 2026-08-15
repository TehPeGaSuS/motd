package io.github.trevarj.motd

import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * Empty Hilt-enabled host for component instrumentation that renders a real app surface.
 *
 * `createComposeRule()` launches a plain `ComponentActivity`, which is not a Hilt entry point, so
 * any composable resolving `hiltViewModel()` fails at composition. Surfaces such as `ChatContent`
 * reach one through `AttachmentSheets`, whose ViewModel default is evaluated whether or not the
 * sheet is open. Hosting those tests here keeps them on the real application graph -- the app is
 * already `@HiltAndroidApp`, so no test application, custom runner, or extra dependency is needed,
 * and the E2E journeys keep running against the real `MotdApplication`.
 *
 * Lives in the `e2e` source set ([testBuildType]), so it never reaches a release build.
 */
@AndroidEntryPoint
class ComposeHostActivity : ComponentActivity()
