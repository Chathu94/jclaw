package controllers;

import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import play.mvc.Controller;
import play.mvc.With;
import slash.Commands;

import java.util.ArrayList;
import java.util.Arrays;

import static utils.GsonHolder.GSON;

/**
 * The built-in slash-command set, for the web composer's {@code /} menu
 * (JCLAW-1071).
 *
 * <p>Derived from {@link Commands.Command} so the menu cannot drift from the set
 * {@link Commands#parse} actually recognizes — the same guarantee
 * {@code TelegramCommandsRegistrationJob} gets by feeding the enum to
 * {@code setMyCommands}.
 */
@With(AuthCheck.class)
public class ApiSlashCommandsController extends Controller {

    private static final Gson gson = GSON;

    /**
     * Wire shape for one command: the {@code /x} form the composer inserts, the
     * bare name, and the one-line description already shared with Telegram's
     * native dropdown.
     *
     * @param webOnly true for a composer-local command the server does not parse
     *                — the client owns its behaviour ({@link Commands.WebCommand})
     */
    public record SlashCommandView(String literal, String name, String description, boolean webOnly) {
        static SlashCommandView of(Commands.Command c) {
            return new SlashCommandView(c.literal, c.bareName(), c.shortDescription, false);
        }

        static SlashCommandView of(Commands.WebCommand c) {
            return new SlashCommandView(c.literal(), c.literal().substring(1), c.shortDescription(), true);
        }
    }

    @ApiResponse(responseCode = "200",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = SlashCommandView.class))))
    @Operation(summary = "List the slash commands offered by the web composer (literal, name, description)")
    public static void list() {
        var all = new ArrayList<SlashCommandView>(
                Arrays.stream(Commands.Command.values()).map(SlashCommandView::of).toList());
        Commands.WEB_ONLY_COMMANDS.stream().map(SlashCommandView::of).forEach(all::add);
        renderJSON(gson.toJson(all));
    }
}
