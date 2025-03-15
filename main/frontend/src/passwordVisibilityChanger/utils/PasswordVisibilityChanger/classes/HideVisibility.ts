import IVisibilityChanger from "../inteface/IVisibilityChanger";

export default  class HideVisibility implements IVisibilityChanger {
    public change(icon: HTMLImageElement, field: HTMLInputElement) : void {
        icon.src = "assets/hide.png";
        icon.dataset.status = "hide";
        field.type = "password";
    }
}