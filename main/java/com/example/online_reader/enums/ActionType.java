package com.example.online_reader.enums;

public enum ActionType {

    Read("Read", "Обладает возможностью чтения"),
    Create("Create", "Обладает возможностью создания"),
    Update("Update", "Обладает возможностью изменения"),
    Delete("Delete", "Обладает возможностью удаления");

    private final String label;
    private final String description;

    ActionType(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String getLabel() {
        return label;
    }

    public String getDescription() {
        return description;
    }
}