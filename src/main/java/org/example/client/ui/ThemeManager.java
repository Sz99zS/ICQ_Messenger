package org.example.client.ui;

import javafx.scene.Scene;

import java.util.List;

/**
 * Менеджер тем оформления (паттерн «Одиночка»).
 *
 * <p>Хранит список CSS-тем и умеет применять/переключать их на сцене на лету.
 * Весь «вайб» интерфейса задаётся внешними CSS-файлами, а не в коде, поэтому
 * новые темы добавляются без перекомпиляции логики.
 */
public final class ThemeManager {

    private static final ThemeManager INSTANCE = new ThemeManager();

    /** Доступные темы (порядок задаёт переключение по кругу). */
    private final List<String> themes = List.of(
            "/org/example/client/css/light.css",
            "/org/example/client/css/dark.css"
    );

    private int current = 0;

    private ThemeManager() { }

    public static ThemeManager getInstance() {
        return INSTANCE;
    }

    /** Применяет текущую тему к сцене (заменяя все предыдущие стили). */
    public void apply(Scene scene) {
        scene.getStylesheets().setAll(resolve(themes.get(current)));
    }

    /** Переключает тему по кругу и применяет к сцене. */
    public void toggle(Scene scene) {
        current = (current + 1) % themes.size();
        apply(scene);
    }

    private String resolve(String path) {
        return getClass().getResource(path).toExternalForm();
    }
}
